// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

const origin = process.env.MIRU_M1_FIXTURE_ORIGIN || 'http://127.0.0.1:8744';
const size = 5 * 1024 * 1024;
const url = `${origin}/download?bytes=${size}`;
const digest = data => createHash('sha256').update(data).digest('hex');
await fetch(`${origin}/control?reset=1`);
const manifest = await fetch(`${origin}/manifest?bytes=${size}`).then(r => r.json());
const head = await fetch(url, { method: 'HEAD' });
assert.equal(head.headers.get('content-length'), `${size}`);
assert.equal(head.headers.get('accept-ranges'), 'bytes');
const parts = await Promise.all(Array.from({ length: 4 }, async (_, i) => {
  const first = i * size / 4, last = (i + 1) * size / 4 - 1;
  const result = await fetch(url, { headers: { Range: `bytes=${first}-${last}`, 'If-Range': manifest.etag } });
  assert.equal(result.status, 206);
  assert.equal(result.headers.get('content-range'), `bytes ${first}-${last}/${size}`);
  return Buffer.from(await result.arrayBuffer());
}));
assert.equal(digest(Buffer.concat(parts)), manifest.sha256);
await fetch(`${origin}/control?bump-etag=1`);
const changed = await fetch(`${origin}/manifest?bytes=${size}`).then(r => r.json());
assert.notEqual(changed.etag, manifest.etag);
assert.notEqual(changed.sha256, manifest.sha256);
const obsolete = await fetch(url, { headers: { Range: 'bytes=1048576-', 'If-Range': manifest.etag } });
assert.equal(obsolete.status, 200);
assert.equal(digest(Buffer.from(await obsolete.arrayBuffer())), changed.sha256);
const invalid = await fetch(url, { headers: { Range: `bytes=${size}-` } });
assert.equal(invalid.status, 416);
assert.equal(invalid.headers.get('content-range'), `bytes */${size}`);
const interrupted = `${origin}/download?bytes=1048576&fail-once=1`;
await assert.rejects(async () => { const result = await fetch(interrupted); await result.arrayBuffer(); });
const retry = await fetch(interrupted);
assert.equal((await retry.arrayBuffer()).byteLength, 1048576);
await fetch(`${origin}/control?reset=1`);
process.stdout.write('M1 fixture self-test passed: HEAD, four ranges, hash, changed validator/body, 416, interrupted body and retry.\n');
