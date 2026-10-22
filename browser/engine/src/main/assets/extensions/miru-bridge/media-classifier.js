// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
// Pure JS shared with future iOS detection. No browser APIs or credential persistence.
globalThis.MiruMedia = (() => {
  function key(raw) {
    try { const url = new URL(raw); return url.origin + url.pathname; } catch (_) { return ''; }
  }
  function classify(raw, mime = '', size = 0) {
    let url;
    try { url = new URL(raw); } catch (_) { return null; }
    if (!['http:', 'https:'].includes(url.protocol)) return null;
    const path = url.pathname.toLowerCase();
    const type = mime.split(';')[0].trim().toLowerCase();
    if (/\.(ts|m4s|aac|cmfv|cmfa)$/.test(path)) return null;
    if (/(^|\.)(doubleclick\.net|googlesyndication\.com|adnxs\.com)$/.test(url.hostname)) return null;
    if (/\.m3u8$/.test(path) || ['application/vnd.apple.mpegurl', 'application/x-mpegurl', 'audio/mpegurl', 'audio/x-mpegurl'].includes(type)) return 'HLS';
    if (/\.mpd$/.test(path) || type === 'application/dash+xml') return 'DASH';
    if (type.startsWith('video/') || /\.(mp4|webm|mkv|flv|mov)$/.test(path)) return size > 0 && size < 200 * 1024 ? null : 'VIDEO';
    if (type.startsWith('audio/') && size > 1024 * 1024) return 'AUDIO';
    return null;
  }
  function headers(values) {
    const result = {};
    for (const item of values || []) if (['referer', 'origin', 'user-agent', 'cookie', 'authorization'].includes(item.name.toLowerCase()) && typeof item.value === 'string') result[item.name] = item.value;
    return result;
  }
  return { key, classify, headers };
})();
