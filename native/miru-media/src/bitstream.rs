// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

use crate::resolution::MediaResolution;

/// RBSP bit reader that strips Annex-B emulation-prevention bytes (`0x00 0x00 0x03`).
struct BitReader {
    bytes: Vec<u8>,
    bit_pos: usize,
}

impl BitReader {
    fn from_nalu(raw: &[u8]) -> Self {
        let mut bytes = Vec::with_capacity(raw.len());
        let mut i = 0;
        while i < raw.len() {
            if i >= 2 && raw[i] == 0x03 && raw[i - 1] == 0x00 && raw[i - 2] == 0x00 {
                i += 1;
                continue;
            }
            bytes.push(raw[i]);
            i += 1;
        }
        Self { bytes, bit_pos: 0 }
    }

    fn bit(&mut self) -> Option<u32> {
        if self.bit_pos >= self.bytes.len() * 8 {
            return None;
        }
        let b = (self.bytes[self.bit_pos / 8] >> (7 - (self.bit_pos % 8))) & 1;
        self.bit_pos += 1;
        Some(b as u32)
    }

    fn read(&mut self, count: usize) -> Option<u32> {
        if count > 32 {
            return None;
        }
        let mut value = 0u32;
        for _ in 0..count {
            value = (value << 1) | self.bit()?;
        }
        Some(value)
    }

    fn skip(&mut self, count: usize) -> Option<()> {
        if self.bit_pos.checked_add(count)? > self.bytes.len() * 8 {
            return None;
        }
        self.bit_pos += count;
        Some(())
    }

    fn ue(&mut self) -> Option<u32> {
        let mut zeros = 0usize;
        while self.bit()? == 0 {
            zeros += 1;
            if zeros > 20 {
                return None;
            }
        }
        let suffix = if zeros == 0 { 0 } else { self.read(zeros)? };
        Some(((1u32 << zeros) - 1) + suffix)
    }

    fn se(&mut self) -> Option<i32> {
        let v = self.ue()?;
        if (v & 1) != 0 {
            Some(((v + 1) / 2) as i32)
        } else {
            Some(-((v / 2) as i32))
        }
    }
}

pub fn parse_avc_sps(raw: &[u8]) -> Option<MediaResolution> {
    let mut b = BitReader::from_nalu(raw);
    let profile = b.read(8)?;
    b.skip(16)?;
    b.ue()?;

    let mut chroma = 1u32;
    let mut separate_plane = false;
    if matches!(profile, 100 | 110 | 122 | 244 | 44 | 83 | 86 | 118 | 128 | 138 | 139 | 134 | 135) {
        chroma = b.ue()?;
        if chroma > 3 {
            return None;
        }
        if chroma == 3 {
            separate_plane = b.bit()? == 1;
        }
        b.ue()?;
        b.ue()?;
        b.skip(1)?;
        if b.bit()? == 1 {
            let lists = if chroma != 3 { 8 } else { 12 };
            for index in 0..lists {
                if b.bit()? == 1 {
                    let size = if index < 6 { 16 } else { 64 };
                    let mut last = 8i32;
                    let mut next = 8i32;
                    for _ in 0..size {
                        if next != 0 {
                            next = (last + b.se()? + 256) % 256;
                        }
                        if next != 0 {
                            last = next;
                        }
                    }
                }
            }
        }
    }

    b.ue()?;
    match b.ue()? {
        0 => {
            b.ue()?;
        }
        1 => {
            b.skip(1)?;
            b.se()?;
            b.se()?;
            let count = b.ue()?;
            if count > 256 {
                return None;
            }
            for _ in 0..count {
                b.se()?;
            }
        }
        _ => {}
    }

    b.ue()?;
    b.skip(1)?;
    let width_mbs = b.ue()? + 1;
    let height_units = b.ue()? + 1;
    if !(1..=1024).contains(&width_mbs) || !(1..=1024).contains(&height_units) {
        return None;
    }
    let frame_mbs_only = b.bit()?;
    if frame_mbs_only == 0 {
        b.skip(1)?;
    }
    b.skip(1)?;

    let mut width = (width_mbs * 16) as i32;
    let mut height = ((2 - frame_mbs_only) * height_units * 16) as i32;
    if b.bit()? == 1 {
        let chroma_array = if separate_plane { 0 } else { chroma };
        let crop_x = if chroma_array == 1 || chroma_array == 2 { 2 } else { 1 };
        let crop_y = (if chroma_array == 1 { 2 } else { 1 }) * (2 - frame_mbs_only) as i32;
        width -= (b.ue()? + b.ue()?) as i32 * crop_x;
        height -= (b.ue()? + b.ue()?) as i32 * crop_y;
    }
    if width <= 0 || height <= 0 {
        return None;
    }
    MediaResolution::valid(width as u32, height as u32)
}

pub fn parse_hevc_sps(raw: &[u8]) -> Option<MediaResolution> {
    let mut b = BitReader::from_nalu(raw);
    b.skip(4)?;
    let layers = b.read(3)? as usize;
    b.skip(1)?;
    b.skip(96)?;

    let mut profiles = vec![false; layers];
    let mut levels = vec![false; layers];
    for i in 0..layers {
        profiles[i] = b.bit()? == 1;
        levels[i] = b.bit()? == 1;
    }
    if layers > 0 {
        b.skip(2 * (8 - layers))?;
    }
    for i in 0..layers {
        if profiles[i] {
            b.skip(88)?;
        }
        if levels[i] {
            b.skip(8)?;
        }
    }

    b.ue()?;
    let chroma = b.ue()?;
    if chroma > 3 {
        return None;
    }
    if chroma == 3 {
        b.skip(1)?;
    }
    let mut width = b.ue()? as i32;
    let mut height = b.ue()? as i32;
    if b.bit()? == 1 {
        let sub_w = if chroma == 1 || chroma == 2 { 2 } else { 1 };
        let sub_h = if chroma == 1 { 2 } else { 1 };
        width -= (b.ue()? + b.ue()?) as i32 * sub_w;
        height -= (b.ue()? + b.ue()?) as i32 * sub_h;
    }
    if width <= 0 || height <= 0 {
        return None;
    }
    MediaResolution::valid(width as u32, height as u32)
}
