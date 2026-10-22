// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
import vm from 'node:vm';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const context = vm.createContext({ URL });
vm.runInContext(fs.readFileSync(new URL('../browser/engine/src/main/assets/extensions/miru-bridge/media-classifier.js', import.meta.url), 'utf8'), context);
const { classify, key, headers } = context.MiruMedia;
assert.equal(classify('https://cdn.example/master?x=1', 'application/vnd.apple.mpegurl', 200), 'HLS');
assert.equal(classify('https://cdn.example/master', 'application/dash+xml', 100), 'DASH');
assert.equal(classify('https://cdn.example/a.mp4?token=1', 'application/octet-stream', 300_000), 'VIDEO');
assert.equal(classify('https://cdn.example/track', 'audio/mp4', 2_000_000), 'AUDIO');
assert.equal(classify('https://cdn.example/track', 'audio/mp4', 900_000), null);
assert.equal(classify('https://cdn.example/preview.mp4', 'video/mp4', 10_000), null);
for(const suffix of ['ts', 'm4s', 'aac']) assert.equal(classify(`https://cdn.example/a.${suffix}`, 'video/mp2t', 2_000_000), null);
assert.equal(classify('https://ads.doubleclick.net/movie.mp4', 'video/mp4', 300_000), null);
assert.equal(classify('blob:https://example.com/123'), null);
assert.equal(key('https://cdn.example/a.mp4?token=old'), key('https://cdn.example/a.mp4?token=new'));
assert.equal(Object.keys(headers([{ name: 'Cookie', value: 'secret' }, { name: 'Authorization', value: 'Bearer secret' }, { name: 'Range', value: 'bytes=0-' }])).join(','), 'Cookie,Authorization');
console.log('Media classifier passed: MIME, signed URLs, audio sizes, preview/segment/ad suppression and header allowlist.');
