// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

use serde::{Deserialize, Serialize};

use crate::bitstream::{parse_avc_sps, parse_hevc_sps};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaResolution {
    pub width: u32,
    pub height: u32,
}

impl MediaResolution {
    pub fn valid(width: u32, height: u32) -> Option<Self> {
        if (64..=16384).contains(&width) && (64..=16384).contains(&height) {
            Some(Self { width, height })
        } else {
            None
        }
    }

    pub fn pixels(self) -> u64 {
        (self.width as u64) * (self.height as u64)
    }

    pub fn label(self) -> &'static str {
        let short_edge = self.width.min(self.height);
        let long_edge = self.width.max(self.height);
        if short_edge >= 2160 || long_edge >= 3840 {
            "4K"
        } else if short_edge >= 1440 || long_edge >= 2560 {
            "2K"
        } else if short_edge >= 1080 || long_edge >= 1920 {
            "1080P"
        } else if short_edge >= 720 || long_edge >= 1280 {
            "720P"
        } else {
            "480P"
        }
    }
}

/// Parses HLS master playlist `#EXT-X-STREAM-INF` / `#EXT-X-I-FRAME-STREAM-INF` RESOLUTION attributes.
pub fn probe_hls_manifest(text: &str) -> Option<MediaResolution> {
    let mut best: Option<MediaResolution> = None;
    for raw_line in text.lines() {
        let line = raw_line.trim();
        let Some(attrs) = line
            .strip_prefix("#EXT-X-STREAM-INF:")
            .or_else(|| line.strip_prefix("#EXT-X-I-FRAME-STREAM-INF:"))
        else {
            continue;
        };
        if let Some(res_val) = extract_hls_attribute(attrs, "RESOLUTION") {
            let mut parts = res_val.split(['x', 'X']);
            let Some(w) = parts.next().and_then(|s| s.parse::<u32>().ok()) else { continue };
            let Some(h) = parts.next().and_then(|s| s.parse::<u32>().ok()) else { continue };
            if parts.next().is_some() {
                continue;
            }
            if let Some(res) = MediaResolution::valid(w, h) {
                if best.map_or(true, |cur| res.pixels() > cur.pixels()) {
                    best = Some(res);
                }
            }
        }
    }
    best
}

fn extract_hls_attribute<'a>(attrs: &'a str, target_key: &str) -> Option<&'a str> {
    let bytes = attrs.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        while i < bytes.len() && (bytes[i] == b',' || bytes[i] == b' ') {
            i += 1;
        }
        let key_start = i;
        while i < bytes.len() && bytes[i] != b'=' && bytes[i] != b',' {
            i += 1;
        }
        if i >= bytes.len() || bytes[i] != b'=' {
            continue;
        }
        let key = attrs[key_start..i].trim();
        i += 1; // '='
        let val = if i < bytes.len() && bytes[i] == b'"' {
            i += 1;
            let start = i;
            while i < bytes.len() && bytes[i] != b'"' {
                i += 1;
            }
            let slice = &attrs[start..i];
            if i < bytes.len() {
                i += 1;
            }
            slice
        } else {
            let start = i;
            while i < bytes.len() && bytes[i] != b',' {
                i += 1;
            }
            attrs[start..i].trim()
        };
        if key.eq_ignore_ascii_case(target_key) {
            return Some(val);
        }
    }
    None
}

#[derive(Clone, Copy)]
struct Mp4Box {
    kind: [u8; 4],
    content: usize,
    end: usize,
    complete: bool,
}

fn parse_mp4_boxes(data: &[u8], start: usize, end: usize) -> Vec<Mp4Box> {
    let mut out = Vec::new();
    let mut offset = start;
    while offset + 8 <= end && out.len() < 8192 {
        let mut size = u32::from_be_bytes([data[offset], data[offset + 1], data[offset + 2], data[offset + 3]]) as usize;
        let mut header = 8usize;
        if size == 1 {
            if offset + 16 > end {
                break;
            }
            let high = u32::from_be_bytes([data[offset + 8], data[offset + 9], data[offset + 10], data[offset + 11]]);
            if high > 0 {
                break;
            }
            size = u32::from_be_bytes([data[offset + 12], data[offset + 13], data[offset + 14], data[offset + 15]]) as usize;
            header = 16;
        } else if size == 0 {
            size = end - offset;
        }
        if size < header {
            break;
        }
        let Some(box_end) = offset.checked_add(size) else { break };
        let kind = [data[offset + 4], data[offset + 5], data[offset + 6], data[offset + 7]];
        out.push(Mp4Box {
            kind,
            content: offset + header,
            end: box_end.min(end),
            complete: box_end <= end,
        });
        if box_end > end {
            break;
        }
        offset = box_end;
    }
    out
}

fn find_child(data: &[u8], parent: Mp4Box, kind: &[u8; 4]) -> Option<Mp4Box> {
    parse_mp4_boxes(data, parent.content, parent.end)
        .into_iter()
        .find(|b| &b.kind == kind)
}

pub fn probe_mp4(data: &[u8]) -> Option<MediaResolution> {
    let mut movies: Vec<Mp4Box> = parse_mp4_boxes(data, 0, data.len())
        .into_iter()
        .filter(|b| &b.kind == b"moov")
        .collect();
    if movies.is_empty() && data.len() >= 8 {
        for i in 4..data.len().saturating_sub(3) {
            if &data[i..i + 4] != b"moov" {
                continue;
            }
            let size = u32::from_be_bytes([data[i - 4], data[i - 3], data[i - 2], data[i - 1]]) as usize;
            if size >= 8 && size <= data.len() - (i - 4) {
                movies.push(Mp4Box {
                    kind: *b"moov",
                    content: i + 4,
                    end: (i - 4) + size,
                    complete: true,
                });
            }
        }
    }

    let mut best: Option<MediaResolution> = None;
    for movie in movies {
        for track in parse_mp4_boxes(data, movie.content, movie.end)
            .into_iter()
            .filter(|b| &b.kind == b"trak")
        {
            let Some(media) = find_child(data, track, b"mdia") else { continue };
            let Some(handler) = find_child(data, media, b"hdlr") else { continue };
            if handler.content + 12 > handler.end || &data[handler.content + 8..handler.content + 12] != b"vide" {
                continue;
            }
            let mut measured: Option<MediaResolution> = None;
            if let Some(stsd) = find_child(data, media, b"minf")
                .and_then(|minf| find_child(data, minf, b"stbl"))
                .and_then(|stbl| find_child(data, stbl, b"stsd"))
            {
                if stsd.content + 8 <= stsd.end {
                    let count = u32::from_be_bytes([
                        data[stsd.content + 4],
                        data[stsd.content + 5],
                        data[stsd.content + 6],
                        data[stsd.content + 7],
                    ])
                    .min(256) as usize;
                    for entry in parse_mp4_boxes(data, stsd.content + 8, stsd.end).into_iter().take(count) {
                        if !matches!(&entry.kind, b"avc1" | b"avc3" | b"hvc1" | b"hev1" | b"av01" | b"vp09" | b"encv" | b"mp4v") {
                            continue;
                        }
                        if entry.content + 28 > entry.end {
                            continue;
                        }
                        let w = u16::from_be_bytes([data[entry.content + 24], data[entry.content + 25]]) as u32;
                        let h = u16::from_be_bytes([data[entry.content + 26], data[entry.content + 27]]) as u32;
                        if let Some(res) = MediaResolution::valid(w, h) {
                            measured = Some(res);
                            break;
                        }
                    }
                }
            }
            if measured.is_none() {
                if let Some(tkhd) = find_child(data, track, b"tkhd") {
                    if tkhd.complete && tkhd.end.saturating_sub(tkhd.content) >= 84 {
                        let w = u32::from_be_bytes([
                            data[tkhd.end - 8],
                            data[tkhd.end - 7],
                            data[tkhd.end - 6],
                            data[tkhd.end - 5],
                        ]) >> 16;
                        let h = u32::from_be_bytes([
                            data[tkhd.end - 4],
                            data[tkhd.end - 3],
                            data[tkhd.end - 2],
                            data[tkhd.end - 1],
                        ]) >> 16;
                        measured = MediaResolution::valid(w, h);
                    }
                }
            }
            if let Some(res) = measured {
                if best.map_or(true, |cur| res.pixels() > cur.pixels()) {
                    best = Some(res);
                }
            }
        }
    }
    best
}

pub fn probe_media_bytes(data: &[u8]) -> Option<MediaResolution> {
    if let Some(res) = probe_mp4(data) {
        return Some(res);
    }
    let start = find_ts_sync(data)?;
    probe_mpeg_ts(data, start)
}

fn find_ts_sync(data: &[u8]) -> Option<usize> {
    if data.len() <= 376 {
        return None;
    }
    let limit = (data.len() - 376).min(4096);
    for i in 0..limit {
        if data[i] == 0x47 && data[i + 188] == 0x47 && data[i + 376] == 0x47 {
            return Some(i);
        }
    }
    None
}

fn probe_mpeg_ts(data: &[u8], start: usize) -> Option<MediaResolution> {
    let mut pmt: Option<u16> = None;
    let mut video_pid: Option<u16> = None;
    let mut is_hevc = false;
    let mut stream = Vec::new();
    let mut p = start;

    while p + 188 <= data.len() {
        if data[p] != 0x47 {
            break;
        }
        let pid = (((data[p + 1] & 0x1f) as u16) << 8) | (data[p + 2] as u16);
        let payload_start = (data[p + 1] & 0x40) != 0;
        let adaptation = (data[p + 3] >> 4) & 3;
        let mut offset = p + 4;
        if (adaptation & 2) != 0 {
            offset += 1 + (data[p + 4] as usize);
        }
        if (adaptation & 1) != 0 && offset < p + 188 {
            if pid == 0 && payload_start {
                let section = offset + 1 + (data[offset] as usize);
                if section + 12 <= p + 188 && data[section] == 0 {
                    let section_len = (((data[section + 1] & 0x0f) as usize) << 8) | (data[section + 2] as usize);
                    let end = (section + 3 + section_len).saturating_sub(4).min(p + 188);
                    let mut i = section + 8;
                    while i + 4 <= end {
                        let prog = ((data[i] as u16) << 8) | (data[i + 1] as u16);
                        if prog != 0 {
                            pmt = Some((((data[i + 2] & 0x1f) as u16) << 8) | (data[i + 3] as u16));
                            break;
                        }
                        i += 4;
                    }
                }
            } else if Some(pid) == pmt && payload_start && video_pid.is_none() {
                let section = offset + 1 + (data[offset] as usize);
                if section + 12 <= p + 188 && data[section] == 2 {
                    let section_len = (((data[section + 1] & 0x0f) as usize) << 8) | (data[section + 2] as usize);
                    let end = (section + 3 + section_len).saturating_sub(4).min(p + 188);
                    let prog_info_len = (((data[section + 10] & 0x0f) as usize) << 8) | (data[section + 11] as usize);
                    let mut i = section + 12 + prog_info_len;
                    while i + 5 <= end {
                        let stream_type = data[i];
                        if stream_type == 0x1b || stream_type == 0x24 {
                            video_pid = Some((((data[i + 1] & 0x1f) as u16) << 8) | (data[i + 2] as u16));
                            is_hevc = stream_type == 0x24;
                            break;
                        }
                        let es_info_len = (((data[i + 3] & 0x0f) as usize) << 8) | (data[i + 4] as usize);
                        i += 5 + es_info_len;
                    }
                }
            } else if Some(pid) == video_pid {
                if payload_start
                    && offset + 9 <= p + 188
                    && data[offset] == 0
                    && data[offset + 1] == 0
                    && data[offset + 2] == 1
                {
                    offset += 9 + (data[offset + 8] as usize);
                }
                if offset < p + 188 {
                    stream.extend_from_slice(&data[offset..p + 188]);
                }
            }
        }
        p += 188;
    }

    if stream.len() < 6 {
        return None;
    }
    for i in 0..stream.len().saturating_sub(5) {
        if stream[i] != 0 || stream[i + 1] != 0 || stream[i + 2] != 1 {
            continue;
        }
        let header = stream[i + 3];
        let skip = if is_hevc { 5 } else { 4 };
        let matches_sps = (!is_hevc && (header & 0x1f) == 7) || (is_hevc && ((header >> 1) & 0x3f) == 33);
        if matches_sps && i + skip < stream.len() {
            let end = (i + 3 + 1024).min(stream.len());
            let nalu = &stream[i + skip..end];
            let res = if is_hevc { parse_hevc_sps(nalu) } else { parse_avc_sps(nalu) };
            if res.is_some() {
                return res;
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn parses_hls_master_manifest_highest_resolution() {
        let playlist = "\
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720
720p.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,CODECS=\"avc1.640028\"
1080p.m3u8
";
        let res = probe_hls_manifest(playlist).expect("HLS resolution");
        assert_eq!(res, MediaResolution { width: 1920, height: 1080 });
        assert_eq!(res.label(), "1080P");
    }

    #[test]
    fn parses_real_hevc_ts_fixture_from_repo() {
        let fixture = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../feature/media-detect/src/test/resources/resolution/hevc.ts");
        let bytes = std::fs::read(&fixture).expect("read hevc.ts fixture");
        let res = probe_media_bytes(&bytes).expect("HEVC TS resolution");
        assert!(res.width >= 64 && res.height >= 64, "unexpected size {res:?}");
    }
}
