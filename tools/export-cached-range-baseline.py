# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
"""Export a fixed packet reference from colors.mp4 without Media3/FFmpeg.

This small independent ISO-BMFF table reader is deliberately limited to the
existing unfragmented single-video fixture with an identity edit. It is not a
production byte/time mapper. Complex fixtures use the separate ffprobe exporter.
"""

import argparse
import hashlib
import json
import pathlib
import struct


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/androidTest/assets/media/seek-preview/colors.mp4"
DESTINATION = ROOT / "app/src/androidTest/assets/media/cached-ranges/colors.samples.json"
EXPECTED_SHA256 = "8259466bc2ec96cfd7d3866f9ae30d325192eea48e4663beed6b9b839f559357"


def u32(data, offset):
    return struct.unpack_from(">I", data, offset)[0]


def boxes(data, start, end):
    while start < end:
        if end - start < 8:
            raise ValueError("Truncated BMFF header")
        size, kind = struct.unpack_from(">I4s", data, start)
        header = 8
        if size == 1:
            size = struct.unpack_from(">Q", data, start + 8)[0]
            header = 16
        elif size == 0:
            size = end - start
        if size < header or size > end - start:
            raise ValueError("BMFF box exceeds its parent")
        yield kind, start, start + size, header
        start += size


def child(data, parent, kind):
    found = [box for box in boxes(data, parent[1] + parent[3], parent[2]) if box[0] == kind]
    if len(found) != 1:
        raise ValueError("Expected exactly one " + kind.decode("ascii"))
    return found[0]


def entries(data, box, width, signed_last=False):
    start = box[1]
    if box[3] != 8:
        raise ValueError("Baseline tables require ordinary box headers")
    count = u32(data, start + 12)
    if start + 16 + count * width * 4 != box[2]:
        raise ValueError("Table size/count mismatch")
    for index in range(count):
        position = start + 16 + index * width * 4
        values = [u32(data, position + column * 4) for column in range(width)]
        if signed_last and values[-1] >= 2**31:
            values[-1] -= 2**32
        yield values


def build_reference():
    data = SOURCE.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != EXPECTED_SHA256:
        raise ValueError("colors.mp4 no longer matches the fixed fixture")
    top = list(boxes(data, 0, len(data)))
    moov = next(box for box in top if box[0] == b"moov")
    mdat = next(box for box in top if box[0] == b"mdat")
    if moov[1] >= mdat[1]:
        raise ValueError("Baseline must have a head moov")
    trak = child(data, moov, b"trak")
    tkhd = child(data, trak, b"tkhd")
    mdia = child(data, trak, b"mdia")
    mdhd = child(data, mdia, b"mdhd")
    hdlr = child(data, mdia, b"hdlr")
    stbl = child(data, child(data, mdia, b"minf"), b"stbl")
    if data[tkhd[1] + 8] != 0 or data[mdhd[1] + 8] != 0:
        raise ValueError("Baseline reader only accepts v0 time/track headers")
    if data[hdlr[1] + 16:hdlr[1] + 20] != b"vide":
        raise ValueError("Baseline must be video")
    track_id = u32(data, tkhd[1] + 20)
    timescale = u32(data, mdhd[1] + 20)
    duration = u32(data, mdhd[1] + 24)
    # Reject a non-identity edit rather than quietly applying an incorrect offset.
    elst = child(data, child(data, trak, b"edts"), b"elst")
    mvhd = child(data, moov, b"mvhd")
    movie_timescale = u32(data, mvhd[1] + 20)
    if (data[elst[1] + 8] != 0 or u32(data, elst[1] + 12) != 1
            or struct.unpack_from(">i", data, elst[1] + 20)[0] != 0
            or struct.unpack_from(">hh", data, elst[1] + 24) != (1, 0)
            or u32(data, elst[1] + 16) * timescale != duration * movie_timescale):
        raise ValueError("Baseline edit is no longer an identity edit")
    stsz = child(data, stbl, b"stsz")
    constant_size = u32(data, stsz[1] + 12)
    sample_count = u32(data, stsz[1] + 16)
    sizes = ([constant_size] * sample_count if constant_size else
             [u32(data, stsz[1] + 20 + index * 4) for index in range(sample_count)])
    chunks = [entry[0] for entry in entries(data, child(data, stbl, b"stco"), 1)]
    runs = list(entries(data, child(data, stbl, b"stsc"), 3))
    offsets = []
    run_index = 0
    sample_index = 0
    for chunk_index, chunk_offset in enumerate(chunks, 1):
        while run_index + 1 < len(runs) and runs[run_index + 1][0] <= chunk_index:
            run_index += 1
        if runs[run_index][0] > chunk_index:
            raise ValueError("Missing stsc chunk mapping")
        cursor = chunk_offset
        for _ in range(runs[run_index][1]):
            offsets.append(cursor)
            cursor += sizes[sample_index]
            sample_index += 1
    deltas = [delta for count, delta in entries(data, child(data, stbl, b"stts"), 2) for _ in range(count)]
    sync = {entry[0] - 1 for entry in entries(data, child(data, stbl, b"stss"), 1)}
    if len(offsets) != sample_count or len(deltas) != sample_count:
        raise ValueError("Expanded tables disagree on sample count")
    if any(box[0] == b"ctts" for box in boxes(data, stbl[1] + stbl[3], stbl[2])):
        raise ValueError("Baseline unexpectedly acquired composition offsets; use ffprobe reference")
    samples = []
    decode_time = 0
    for index, (offset, size, delta) in enumerate(zip(offsets, sizes, deltas)):
        if not (mdat[1] + mdat[3] <= offset < offset + size <= mdat[2]):
            raise ValueError("Sample points outside the actual mdat payload")
        pts_us = decode_time * 1_000_000 // timescale
        samples.append({
            "offset": offset, "size": size, "ptsUs": pts_us, "dtsUs": pts_us,
            "durationUs": delta * 1_000_000 // timescale, "isSync": index in sync,
            "sha256": hashlib.sha256(data[offset:offset + size]).hexdigest(),
        })
        decode_time += delta
    if decode_time != duration or sample_count != 120 or timescale != 10240:
        raise ValueError("Baseline duration/count/timescale changed")
    return {
        "schemaVersion": 1, "asset": "media/seek-preview/colors.mp4",
        "sha256": digest, "bytes": len(data), "moovPlacement": "head",
        "ignoreEditLists": False,
        "provenance": {
            "exporter": "tools/export-cached-range-baseline.py",
            "method": "Independent ISO-BMFF stco/stsc/stsz/stts/stss expansion; identity elst checked; sample payload hashes",
            "media3Used": False, "ffmpegExecutedByExporter": False,
            "originalFixtureManifest": "media/seek-preview/manifest.json",
        },
        "tracks": [{
            "id": track_id, "type": "video", "timescale": timescale,
            "sampleCount": sample_count, "durationUs": duration * 1_000_000 // timescale,
            "samples": samples,
        }],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Compare fixed reference without writing")
    options = parser.parse_args()
    reference = build_reference()
    encoded = json.dumps(reference, indent=2) + "\n"
    if options.check:
        if DESTINATION.read_text(encoding="utf-8") != encoded:
            raise SystemExit("Fixed packet reference differs")
    else:
        DESTINATION.parent.mkdir(parents=True, exist_ok=True)
        DESTINATION.write_text(encoded, encoding="utf-8", newline="\n")
    print("Fixed baseline: 120 source samples, exact offsets/PTS/payload SHA-256; no Media3 or FFmpeg executed.")


if __name__ == "__main__":
    main()
