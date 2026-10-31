# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
"""Generate owned SegmentBase/sidx, SegmentList and PTO fixtures, with byte/decoder oracles."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    project = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=project / "app/src/androidTest/assets/media/cached-ranges/dash")
    parser.add_argument("--evidence", type=Path, default=project / "artifacts/m3-cached-ranges/dash-fixture-generation-02")
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
    parser.add_argument("--ffprobe", default=shutil.which("ffprobe"))
    args = parser.parse_args()
    output, evidence = args.output.resolve(), args.evidence.resolve()
    output.mkdir(parents=True, exist_ok=True)
    evidence.mkdir(parents=True, exist_ok=True)
    source = project / "app/src/androidTest/assets/media/seek-preview/colors.mp4"
    target = output / "base.mp4"
    command = [args.ffmpeg, "-hide_banner", "-loglevel", "error", "-i", str(source), "-map", "0:v:0", "-an", "-c", "copy",
               "-movflags", "empty_moov+default_base_moof+dash+global_sidx", "-frag_duration", "4000000",
               "-min_frag_duration", "4000000", "-f", "mp4", "-y", str(target)]
    result = subprocess.run(command, capture_output=True)
    (evidence / "generation.stderr").write_bytes(result.stderr)
    assert result.returncode == 0, result.returncode
    data, atoms, position = target.read_bytes(), [], 0
    while position < len(data):
        size, kind = struct.unpack_from(">I4s", data, position)
        header = 8
        if size == 1:
            size = struct.unpack_from(">Q", data, position + 8)[0]
            header = 16
        if size == 0:
            size = len(data) - position
        assert size >= header and position + size <= len(data)
        atoms.append(dict(position=position, size=size, type=kind.decode("ascii")))
        position += size
    sidx = next(item for item in atoms if item["type"] == "sidx")
    cursor = sidx["position"] + 8
    version = data[cursor]
    cursor += 4
    reference, timescale = struct.unpack_from(">II", data, cursor)
    cursor += 8
    if version == 0:
        earliest, first_offset = struct.unpack_from(">II", data, cursor)
        cursor += 8
    else:
        earliest, first_offset = struct.unpack_from(">QQ", data, cursor)
        cursor += 16
    cursor += 2
    count = struct.unpack_from(">H", data, cursor)[0]
    cursor += 2
    segments = []
    offset, time = sidx["position"] + sidx["size"] + first_offset, earliest
    for number in range(count):
        size, duration, sap = struct.unpack_from(">III", data, cursor)
        cursor += 12
        assert size >> 31 == 0
        segments.append(dict(position=offset, length=size, start=time, duration=duration,
                             startsWithSAP=bool(sap >> 31), sapType=(sap >> 28) & 7))
        offset += size
        time += duration
    assert count == 3 and all(item["duration"] * 1_000_000 == timescale * 4_000_000 for item in segments)
    assert offset <= len(data)
    # FFmpeg's SAP_type is retained verbatim (including 0). Do not rewrite it to
    # manufacture proof. The derived MPD declaration is backed by separate decodes.
    decoded = []
    for number, (segment, wanted) in enumerate(zip(segments, ((255, 0, 0), (0, 255, 0), (0, 0, 255)))):
        part = evidence / f"independent-{number}.mp4"
        part.write_bytes(data[:sidx["position"]] + data[segment["position"]:segment["position"] + segment["length"]])
        probe_command = [args.ffprobe, "-v", "error", "-select_streams", "v:0", "-show_packets", "-show_entries",
                         "packet=pts_time,dts_time,flags", "-of", "json", "-read_intervals", "%+#1", str(part)]
        probe = subprocess.run(probe_command, capture_output=True, check=True)
        packets = json.loads(probe.stdout)["packets"]
        assert len(packets) == 1 and "K" in packets[0]["flags"]
        assert abs(float(packets[0]["pts_time"]) - number * 4) <= 0.000001
        decode_command = [args.ffmpeg, "-hide_banner", "-loglevel", "error", "-i", str(part), "-map", "0:v:0",
                          "-frames:v", "1", "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"]
        frame = subprocess.run(decode_command, capture_output=True, check=True).stdout
        assert len(frame) == 160 * 90 * 3
        rgb = [sum(frame[(y * 160 + x) * 3 + channel] for y in range(15, 36) for x in range(55, 96)) / (21 * 41) for channel in range(3)]
        assert all(abs(one - two) <= 45 for one, two in zip(rgb, wanted)), (number, rgb)
        (evidence / f"independent-{number}.rgb").write_bytes(frame)
        decoded.append(dict(probeCommand=probe_command, firstPacket=packets[0], decodeCommand=decode_command,
                            rgb=rgb, frameSHA256=hashlib.sha256(frame).hexdigest()))
    header = ('<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT12S" minBufferTime="PT1S">'
              '<Period id="p0" start="PT0S"><AdaptationSet id="0" contentType="video" startWithSAP="1" segmentAlignment="true">'
              '<Representation id="v" mimeType="video/mp4" codecs="avc1.42c00a" bandwidth="20000" width="160" height="90"><BaseURL>base.mp4</BaseURL>')
    footer = '</Representation></AdaptationSet></Period></MPD>'
    base = header + f'<SegmentBase indexRange="{sidx["position"]}-{sidx["position"] + sidx["size"] - 1}"><Initialization range="0-{sidx["position"] - 1}"/></SegmentBase>' + footer
    (output / "base.mpd").write_text(base, encoding="utf-8", newline="\n")
    listing = header + f'<SegmentList timescale="{timescale}" duration="{segments[0]["duration"]}"><Initialization range="0-{sidx["position"] - 1}"/>'
    listing += ''.join(f'<SegmentURL mediaRange="{item["position"]}-{item["position"] + item["length"] - 1}"/>' for item in segments)
    (output / "list.mpd").write_text(listing + '</SegmentList>' + footer, encoding="utf-8", newline="\n")
    pto = base.replace('mediaPresentationDuration="PT12S"', 'mediaPresentationDuration="PT10S"').replace('<SegmentBase ', f'<SegmentBase timescale="{timescale}" presentationTimeOffset="{timescale * 2}" ')
    (output / "pto.mpd").write_text(pto, encoding="utf-8", newline="\n")
    report = dict(command=command, ffmpegSHA256=sha(Path(args.ffmpeg)), ffprobeSHA256=sha(Path(args.ffprobe)),
                  sourceSHA256=sha(source), outputSHA256=sha(target), bytes=len(data), atoms=atoms, index=sidx,
                  timescale=timescale, segments=segments, independentDecodes=decoded)
    (output / "manifest.json").write_text(json.dumps(report, indent=2), encoding="utf-8", newline="\n")
    print(json.dumps(dict(bytes=len(data), sha256=sha(target), segments=segments, independentDecodes=len(decoded))))


if __name__ == "__main__":
    main()
