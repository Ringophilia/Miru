# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
"""Losslessly repeat the owned colors clip, pin tools and export exact packet truth."""
import hashlib
import json
import pathlib
import shutil
import subprocess
from fractions import Fraction

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/androidTest/assets/media/seek-preview/colors.mp4"
OUT = ROOT / "app/src/androidTest/assets/media/cached-ranges"
def sha(data): return hashlib.sha256(data).hexdigest()
def main():
    ffmpeg, ffprobe = (shutil.which(n) for n in ("ffmpeg", "ffprobe"))
    assert ffmpeg and ffprobe
    assert sha(SOURCE.read_bytes()) == "8259466bc2ec96cfd7d3866f9ae30d325192eea48e4663beed6b9b839f559357"
    assert sha(pathlib.Path(ffmpeg).read_bytes()) == "6d1169549f6a67de04dfc1d1868b514d594e365a1104fd384cbe851ded7108f8"
    assert sha(pathlib.Path(ffprobe).read_bytes()) == "3ca44c072baeb38eaf21f32f8bf527718c5cd2ee0bc49122e187b7c1ab595644"
    output = OUT / "long-colors.mp4"
    command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-stream_loop", "9", "-i", str(SOURCE), "-map", "0:v:0", "-c:v", "copy", "-an", "-movflags", "+faststart", "-y", str(output)]
    subprocess.run(command, check=True)
    probe = [ffprobe, "-v", "error", "-show_packets", "-show_streams", "-show_entries", "stream=id,codec_type,time_base,duration_ts:packet=pos,size,pts,dts,duration,flags", "-of", "json", str(output)]
    data = json.loads(subprocess.check_output(probe))
    body = output.read_bytes(); stream = data["streams"][0]
    assert len(data["streams"]) == 1 and stream["codec_type"] == "video"
    scale = Fraction(stream["time_base"]); assert scale == Fraction(1, 10240)
    original = json.loads((OUT / "colors.samples.json").read_text(encoding="utf-8"))["tracks"][0]["samples"]
    samples = []
    assert len(data["packets"]) == 1200
    for i, p in enumerate(data["packets"]):
        offset, size = int(p["pos"]), int(p["size"])
        assert int(p["pts"]) == int(p["dts"]) == i * 1024 and int(p["duration"]) == 1024
        payload = sha(body[offset:offset + size]); assert payload == original[i % 120]["sha256"]
        samples.append(dict(offset=offset, size=size, ptsUs=int(int(p["pts"]) * scale * 1_000_000), dtsUs=int(int(p["dts"]) * scale * 1_000_000), durationUs=100000,
                            isSync="K" in p["flags"], sha256=payload))
    reference = dict(schemaVersion=1, asset="media/cached-ranges/long-colors.mp4", sha256=sha(body), bytes=len(body), moovPlacement="head", ignoreEditLists=False,
        provenance=dict(exporter="tools/generate-long-mp4-cache-fixture.py", sourceSha256=sha(SOURCE.read_bytes()), ffmpegSha256=sha(pathlib.Path(ffmpeg).read_bytes()),
                        ffprobeSha256=sha(pathlib.Path(ffprobe).read_bytes()), command=command, packetProbe=probe, method="Lossless stream_loop copy; all 1200 payloads match original 120 samples cyclically; integer packet truth", media3Used=False),
        tracks=[dict(id=int(stream["id"], 0), type="video", timescale=10240, sampleCount=1200, durationUs=120_000_000, samples=samples)])
    (OUT / "long-colors.samples.json").write_text(json.dumps(reference, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({k:reference[k] for k in ("sha256", "bytes")}))
if __name__ == "__main__": main()
