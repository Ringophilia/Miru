# Resolution parser fixture

`hevc.ts` is a one-frame, silent synthetic FFmpeg test pattern (320 × 180),
encoded as H.265 in MPEG-TS. It validates SPS cropping and TS demuxing without
requiring a decoder or a public website. No third-party video is included.

Generated with FFmpeg 8 / libx265:

```powershell
ffmpeg -v error -f lavfi -i 'testsrc2=size=320x180:rate=1' -frames:v 1 -an -c:v libx265 -preset ultrafast -x265-params 'log-level=error:pools=1:frame-threads=1' -f mpegts hevc.ts
```
