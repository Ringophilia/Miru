# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
"""Owned color video + two AAC tones; independently pin every original packet."""
import hashlib
import argparse
import json
import pathlib
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/src/androidTest/assets/media/cached-ranges'
VIDEO = ROOT / 'app/src/androidTest/assets/media/seek-preview/colors.mp4'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--long', action='store_true', help='Use the owned 120-second video and two 120-second tones')
    args = parser.parse_args()
    seconds = 120 if args.long else 12
    video = OUT / 'long-colors.mp4' if args.long else VIDEO
    baseline_name = 'long-colors' if args.long else 'colors'
    baseline_reference = json.loads((OUT / (baseline_name + '.samples.json')).read_text(encoding='utf-8'))
    ffmpeg, ffprobe = (shutil.which(name) for name in ('ffmpeg', 'ffprobe'))
    assert ffmpeg and ffprobe
    assert digest(pathlib.Path(ffmpeg).read_bytes()) == '6d1169549f6a67de04dfc1d1868b514d594e365a1104fd384cbe851ded7108f8'
    assert digest(pathlib.Path(ffprobe).read_bytes()) == '3ca44c072baeb38eaf21f32f8bf527718c5cd2ee0bc49122e187b7c1ab595644'
    assert digest(video.read_bytes()) == baseline_reference['sha256']
    if not args.long:
        assert digest(video.read_bytes()) == '8259466bc2ec96cfd7d3866f9ae30d325192eea48e4663beed6b9b839f559357'
    name = 'long-av-colors' if args.long else 'av-colors'
    movie, reference = OUT / (name + '.mp4'), OUT / (name + '.raw.samples.json')
    assert not movie.exists() and not reference.exists(), 'Refuse to overwrite owned fixture evidence'
    command = [ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin', '-i', str(video),
               '-f', 'lavfi', '-i', f'sine=frequency=440:sample_rate=44100:duration={seconds}',
               '-f', 'lavfi', '-i', f'sine=frequency=880:sample_rate=44100:duration={seconds}',
               '-map', '0:v:0', '-map', '1:a:0', '-map', '2:a:0', '-c:v', 'copy', '-c:a', 'aac',
               '-b:a', '48k', '-metadata:s:a:0', 'language=eng', '-metadata:s:a:1', 'language=jpn',
               '-movflags', '+faststart', str(movie)]
    subprocess.run(command, check=True)
    probe = [ffprobe, '-v', 'error', '-ignore_editlist', '1', '-show_packets', '-show_streams',
             '-show_entries', 'stream=index,id,codec_type,codec_name,time_base:packet=stream_index,pos,size,pts,dts,duration,flags',
             '-of', 'json', str(movie)]
    raw = json.loads(subprocess.check_output(probe))
    body = movie.read_bytes()
    assert [s['codec_name'] for s in raw['streams']] == ['h264', 'aac', 'aac']
    tracks = []
    for stream in raw['streams']:
        samples = []
        for packet in raw['packets']:
            if packet['stream_index'] != stream['index']:
                continue
            offset, size = int(packet['pos']), int(packet['size'])
            assert 0 <= offset < offset + size <= len(body)
            samples.append(dict(offset=offset, size=size, ptsTicks=int(packet['pts']), dtsTicks=int(packet['dts']),
                                durationTicks=int(packet['duration']), isSync='K' in packet['flags'],
                                sha256=digest(body[offset:offset + size])))
        tracks.append(dict(id=int(stream['id'], 0), type=stream['codec_type'], codec=stream['codec_name'],
                           timeBase=stream['time_base'], sampleCount=len(samples), samples=samples))
    baseline = baseline_reference['tracks'][0]['samples']
    assert [p['sha256'] for p in tracks[0]['samples']] == [p['sha256'] for p in baseline]
    report = dict(schemaVersion=1, asset=f'media/cached-ranges/{name}.mp4', bytes=len(body), sha256=digest(body),
                  ignoreEditLists=True, tracks=tracks,
                  provenance=dict(generator='tools/generate-mp4-av-cache-fixture.py', command=command, probe=probe,
                                  ffmpegSha256=digest(pathlib.Path(ffmpeg).read_bytes()),
                                  ffprobeSha256=digest(pathlib.Path(ffprobe).read_bytes()),
                                  method='Owned video payloads unchanged; two generated tones, raw packet reference independent of Media3'))
    reference.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps(dict(bytes=len(body), sha256=report['sha256'], sampleCounts=[t['sampleCount'] for t in tracks])))


if __name__ == '__main__':
    main()
