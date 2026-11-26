# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
"""Packages built ABI AAR(s) from Chromium dist_aar + uncompressed Chromium assets into local Maven repo."""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import xml.etree.ElementTree as ET
import zipfile

ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
ET.register_namespace("xliff", "urn:oasis:names:tc:xliff:document:1.2")
ET.register_namespace("tools", "http://schemas.android.com/tools")

ASSET_MAPPINGS: tuple[tuple[str, str], ...] = (
    ("miru_chromium.pak", "miru_chromium.pak"),
    ("icudtl.dat", "icudtl.dat"),
    ("snapshot_blob_64.bin", "snapshot_blob_64.bin"),
    ("snapshot_blob.bin", "snapshot_blob_64.bin"),
    ("v8_context_snapshot.bin", "v8_context_snapshot.bin"),
)

EXCLUDED_CLASS_PREFIXES: tuple[str, ...] = (
    "android/support/v4/",
    "com/google/common/",
    "com/google/thirdparty/",
    "org/jspecify/",
    "play-services-",
)


def package_aar(
    repo_root: pathlib.Path,
    version: str,
    abi_out_dirs: dict[str, pathlib.Path],
) -> pathlib.Path:
    if not abi_out_dirs:
        raise SystemExit("At least one ABI build directory is required.")

    maven_dir = (
        repo_root
        / "tools"
        / "local-chromium-repo"
        / "app"
        / "miru"
        / "miru-chromium-engine"
        / version
    )
    maven_dir.mkdir(parents=True, exist_ok=True)
    out_aar = maven_dir / f"miru-chromium-engine-{version}.aar"
    out_pom = maven_dir / f"miru-chromium-engine-{version}.pom"
    sha_file = maven_dir / "SHA256SUMS"

    first_abi, first_dir = next(iter(abi_out_dirs.items()))
    base_aar = first_dir / f"miru_chromium_engine_{first_abi}.aar"
    if not base_aar.is_file():
        raise FileNotFoundError(f"Missing dist_aar output: {base_aar}")

    tmp_aar = maven_dir / f".tmp-{version}.aar"
    written_entries: set[str] = set()

    with zipfile.ZipFile(base_aar, "r") as zin, zipfile.ZipFile(tmp_aar, "w") as zout:
        # 1. Copy base AAR entries (manifest, classes.jar, res, R.txt, proguard.txt, first ABI jni/).
        seen_attr_formats: set[str] = set()
        for info in sorted(zin.infolist(), key=lambda i: i.filename):
            if info.filename.startswith("assets/"):
                continue
            data = zin.read(info.filename)
            if info.filename == "classes.jar":
                import io
                buf = io.BytesIO()
                compliment_jar = first_dir / "obj" / "miru_engine" / "miru_engine_aar_java.compliment.jar"
                has_gen_jni = False
                with zipfile.ZipFile(io.BytesIO(data), "r") as cin, zipfile.ZipFile(buf, "w") as cout:
                    for cinfo in cin.infolist():
                        if cinfo.filename.startswith(EXCLUDED_CLASS_PREFIXES):
                            continue
                        if cinfo.filename == "org/jni_zero/GEN_JNI.class":
                            has_gen_jni = True
                        cout.writestr(cinfo.filename, cin.read(cinfo.filename), compress_type=zipfile.ZIP_DEFLATED)
                    if not has_gen_jni:
                        if not compliment_jar.is_file():
                            raise FileNotFoundError(f"Missing compliment jar for GEN_JNI.class: {compliment_jar}")
                        with zipfile.ZipFile(compliment_jar, "r") as ccomp:
                            for cinfo in ccomp.infolist():
                                if cinfo.filename == "org/jni_zero/GEN_JNI.class":
                                    cout.writestr(cinfo.filename, ccomp.read(cinfo.filename), compress_type=zipfile.ZIP_DEFLATED)
                                    has_gen_jni = True
                    if not has_gen_jni:
                        raise FileNotFoundError(f"org/jni_zero/GEN_JNI.class missing in {compliment_jar}")
                data = buf.getvalue()
            elif info.filename == "R.txt":
                # dist_aar merges transitive R.txt files verbatim and emits 1-element dummy styleable
                # arrays with index 1; AGP rejects duplicate (type, name) and drops out-of-bounds indices.
                raw_lines = [l for l in data.decode("utf-8").splitlines() if l.strip()]
                deduped: list[str] = []
                seen_symbols: set[tuple[str, str]] = set()
                styleable_arrays: set[str] = set()
                for line in raw_lines:
                    parts = line.split()
                    if len(parts) >= 3 and parts[0] == "int[]" and parts[1] == "styleable":
                        styleable_arrays.add(parts[2])
                styleable_children: dict[str, list[str]] = {p: [] for p in styleable_arrays}
                # Match longest parent prefix first in case a styleable name is a prefix of another.
                sorted_parents = sorted(styleable_arrays, key=len, reverse=True)
                for line in raw_lines:
                    parts = line.split()
                    if len(parts) >= 3:
                        key = (parts[1], parts[2])
                        if key in seen_symbols:
                            continue
                        seen_symbols.add(key)
                        if parts[0] == "int" and parts[1] == "styleable":
                            parent = next((p for p in sorted_parents if parts[2].startswith(p + "_")), None)
                            if parent is not None:
                                styleable_children[parent].append(parts[2])
                                continue
                        elif parts[0] == "int[]" and parts[1] == "styleable":
                            continue
                    deduped.append(line)
                for parent in sorted(styleable_arrays):
                    children = styleable_children[parent]
                    for idx, child_name in enumerate(children):
                        deduped.append(f"int styleable {child_name} {idx}")
                    if children:
                        vals = ", ".join(f"0x{0x7f010001 + i:08x}" for i in range(len(children)))
                        deduped.append(f"int[] styleable {parent} {{ {vals} }}")
                    else:
                        deduped.append(f"int[] styleable {parent} {{ }}")
                data = ("\n".join(deduped) + "\n").encode("utf-8")
            elif info.filename.startswith("res/values/") and info.filename.endswith(".xml"):
                root = ET.fromstring(data)
                modified = False
                for child in list(root):
                    if child.tag == "attr" and "name" in child.attrib and "format" in child.attrib:
                        name = child.attrib["name"]
                        if name in seen_attr_formats:
                            root.remove(child)
                            modified = True
                        else:
                            seen_attr_formats.add(name)
                    else:
                        for sub in child.iter("attr"):
                            name = sub.attrib.get("name")
                            if name and "format" in sub.attrib:
                                if name in seen_attr_formats:
                                    sub.attrib.pop("format", None)
                                    modified = True
                                else:
                                    seen_attr_formats.add(name)
                if modified:
                    data = ET.tostring(root, encoding="utf-8", xml_declaration=True)
            compress = (
                zipfile.ZIP_STORED
                if info.filename.endswith(".so")
                else zipfile.ZIP_DEFLATED
            )
            zout.writestr(info.filename, data, compress_type=compress)
            written_entries.add(info.filename)

        # 2. Merge remaining ABI native libraries (e.g. arm64-v8a + x86_64).
        for abi, out_dir in abi_out_dirs.items():
            abi_aar = out_dir / f"miru_chromium_engine_{abi}.aar"
            if abi == first_abi:
                continue
            if not abi_aar.is_file():
                raise FileNotFoundError(f"Missing dist_aar output for {abi}: {abi_aar}")
            with zipfile.ZipFile(abi_aar, "r") as zother:
                for info in zother.infolist():
                    if info.filename.startswith("jni/") and info.filename not in written_entries:
                        zout.writestr(
                            info.filename,
                            zother.read(info.filename),
                            compress_type=zipfile.ZIP_STORED,
                        )
                        written_entries.add(info.filename)

        # 3. Inject uncompressed assets required by AssetManager.openFd() at Chromium startup.
        for src_name, dst_name in ASSET_MAPPINGS:
            candidate = first_dir / src_name
            if candidate.is_file():
                entry_name = f"assets/{dst_name}"
                if entry_name not in written_entries:
                    zout.writestr(
                        entry_name,
                        candidate.read_bytes(),
                        compress_type=zipfile.ZIP_STORED,
                    )
                    written_entries.add(entry_name)

    tmp_aar.replace(out_aar)

    pom_xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>app.miru</groupId>
  <artifactId>miru-chromium-engine</artifactId>
  <version>{version}</version>
  <packaging>aar</packaging>
</project>
"""
    out_pom.write_text(pom_xml, encoding="utf-8", newline="\n")

    lines = []
    for f in (out_aar, out_pom):
        digest = hashlib.sha256(f.read_bytes()).hexdigest()
        lines.append(f"{digest}  {f.name}")
    sha_file.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")

    print(f"[package-aar] Published {out_aar} ({out_aar.stat().st_size:,} B)")
    for line in lines:
        print(f"  {line}")
    return out_aar


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", default="159.0.8154.33")
    parser.add_argument("--x86-64-out", type=pathlib.Path)
    parser.add_argument("--arm64-out", type=pathlib.Path)
    args = parser.parse_args()

    abi_dirs: dict[str, pathlib.Path] = {}
    if args.arm64_out:
        abi_dirs["arm64-v8a"] = args.arm64_out
    if args.x86_64_out:
        abi_dirs["x86_64"] = args.x86_64_out

    repo_root = pathlib.Path(__file__).resolve().parents[2]
    package_aar(repo_root, args.version, abi_dirs)


if __name__ == "__main__":
    main()
