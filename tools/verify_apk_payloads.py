# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
"""Verify that APK recompression/signing preserved every application payload."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import zipfile


SIGNATURE_ENTRY = re.compile(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", re.IGNORECASE)


def digest(stream):
    result = hashlib.sha256()
    while chunk := stream.read(128 * 1024):
        result.update(chunk)
    return result.hexdigest()


def file_digest(path):
    with path.open("rb") as stream:
        return digest(stream)


def inventory(path):
    payloads, signatures = {}, []
    with zipfile.ZipFile(path) as archive:
        counts = collections.Counter(item.filename for item in archive.infolist())
        if any(count != 1 for count in counts.values()):
            raise ValueError("Duplicate ZIP entry names")
        for item in archive.infolist():
            if SIGNATURE_ENTRY.fullmatch(item.filename):
                signatures.append(item.filename)
                continue
            with archive.open(item) as stream:
                payloads[item.filename] = {
                    "bytes": item.file_size,
                    "crc32": f"{item.CRC:08x}",
                    "sha256": digest(stream),
                    "compressionMethod": item.compress_type,
                }
    return payloads, sorted(signatures)


def verify(original, candidate, max_bytes=None):
    original, candidate = Path(original).resolve(), Path(candidate).resolve()
    expected, original_signatures = inventory(original)
    actual, candidate_signatures = inventory(candidate)
    if expected.keys() != actual.keys():
        raise ValueError("Application payload paths changed")
    for name in sorted(expected):
        if expected[name] != actual[name]:
            raise ValueError(f"Application payload changed: {name}")
    size = candidate.stat().st_size
    if max_bytes is not None and size >= max_bytes:
        raise ValueError(f"APK size {size} is not strictly below {max_bytes}")
    return {
        "status": "PASSED",
        "scope": "Non-signature ZIP paths, lengths, CRC32, SHA-256 and compression methods; not runtime or certificate verification",
        "original": str(original),
        "candidate": str(candidate),
        "originalSha256": file_digest(original),
        "candidateSha256": file_digest(candidate),
        "originalBytes": original.stat().st_size,
        "candidateBytes": size,
        "strictMaximumBytes": max_bytes,
        "verifiedPayloadEntries": len(actual),
        "originalSignatureEntries": original_signatures,
        "candidateSignatureEntries": candidate_signatures,
        "payloads": actual,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--max-bytes", type=int)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = verify(args.original, args.candidate, args.max_bytes)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        result = {"status": "FAILED", "error": str(error)}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "payloads"}, indent=2))
    return 0 if result["status"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
