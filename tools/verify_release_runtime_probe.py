# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
"""Reject test APKs that define foreign runtime classes before loading them into R8."""
import argparse
import hashlib
import json
import pathlib
import struct
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=pathlib.Path, required=True)
    parser.add_argument('--report', type=pathlib.Path, required=True)
    args = parser.parse_args()
    classes = []
    compiler_metadata = []
    with zipfile.ZipFile(args.apk) as archive:
        for name in archive.namelist():
            if not name.endswith('.dex'):
                continue
            data = archive.read(name)
            def uint(offset):
                return struct.unpack_from('<I', data, offset)[0]
            strings, types = uint(60), uint(68)
            for index in range(uint(96)):
                offset = uint(strings + uint(types + uint(uint(100) + index * 32) * 4) * 4)
                for _ in range(5):
                    byte = data[offset]
                    offset += 1
                    if byte < 128:
                        break
                else:
                    raise ValueError('Invalid DEX string length')
                name = data[offset:data.index(0, offset)].decode('utf-8')
                classes.append(name)
                # D8 may emit this marker alongside Java lambda desugaring. It is
                # an annotation, not a target API implementation or runtime library.
                if name == 'Lcom/android/tools/r8/annotations/LambdaMethod;' and uint(uint(100) + index * 32 + 4) & 0x2000:
                    compiler_metadata.append(name)
    if not classes or any(not name.startswith('Lapp/miru/releaseprobe/') and name not in compiler_metadata for name in classes):
        raise ValueError('Probe must define only its own classes; foreign classes can shadow R8-renamed target APIs')
    apk = args.apk.resolve()
    report = dict(status='BUILT_NOT_RUN', apk=str(apk), bytes=apk.stat().st_size,
                  sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), noRuntimeDependencies=True,
                  definedClasses=classes, compilerMetadata=compiler_metadata, targetPackage='app.miru.browser',
                  instrumentation='app.miru.releaseprobe/app.miru.releaseprobe.ReleaseRuntimeProbe')
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(status='PASSED', definedClasses=len(classes), bytes=report['bytes'], sha256=report['sha256'])))


if __name__ == '__main__':
    main()
