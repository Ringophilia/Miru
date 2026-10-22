// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

// Deterministic M1 acceptance fixture. Usage: node tools/m1-fixture-server.mjs
// Emulator: http://10.0.2.2:8744/ . No dependency on npm or the public internet.
import http from 'node:http';
import { createHash } from 'node:crypto';

const port = Number(process.env.MIRU_M1_FIXTURE_PORT || 8744);
const maxBytes = 512 * 1024 * 1024;
const block = Buffer.alloc(64 * 1024);
for (let i = 0; i < block.length; i++) block[i] = (i * 31 + (i >>> 8) * 17 + 83) & 255;
const requests = [];
const faults = new Set();
let etagVersion = 1;
function sizeOf(url) {
  return Math.max(1, Math.min(maxBytes, Number(url.searchParams.get('bytes') || 5 * 1024 * 1024)));
}
function bytesAt(start, length, version = etagVersion) {
  const buffer = Buffer.alloc(length);
  for (let offset = 0; offset < length;) {
    const from = (start + offset) % block.length;
    const count = Math.min(length - offset, block.length - from);
    block.copy(buffer, offset, from, from + count);
    offset += count;
  }
  if (version !== 1) for (let i = 0; i < buffer.length; i++) buffer[i] ^= (version - 1) & 255;
  return buffer;
}
function sha256(size) {
  const hash = createHash('sha256');
  for (let offset = 0; offset < size; offset += block.length) hash.update(bytesAt(offset, Math.min(block.length, size - offset)));
  return hash.digest('hex');
}
function json(response, body, code = 200) {
  response.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(body));
}
const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, 'http://localhost');
  requests.push({ method: request.method, path: url.pathname, query: url.search, range: request.headers.range || null, ifRange: request.headers['if-range'] || null, at: Date.now() });
  if (requests.length > 4096) requests.shift();
  if (url.pathname === '/control') {
    if (url.searchParams.has('reset')) { requests.length = 0; faults.clear(); etagVersion = 1; }
    if (url.searchParams.has('bump-etag')) etagVersion++;
    return json(response, { etagVersion });
  }
  if (url.pathname === '/requests') return json(response, requests);
  if (url.pathname === '/manifest') {
    const size = sizeOf(url);
    return json(response, { bytes: size, sha256: sha256(size), etag: `"miru-fixture-v${etagVersion}-${size}"` });
  }
  if (url.pathname === '/miru-test-ad.js' || url.pathname === '/ads/custom_ads.js') {
    response.writeHead(200, { 'Content-Type': 'text/javascript', 'Cache-Control': 'no-store' });
    return response.end('document.documentElement.dataset.miruAdLoaded="true";');
  }
  if (url.pathname === '/cold-page') {
    const body = '<!doctype html><meta name="viewport" content="width=device-width"><title>Miru cold loading</title><h1>Miru cold-start fixture</h1><script src="/ads/custom_ads.js"></script><script>document.title=document.documentElement.dataset.miruAdLoaded==="true"?"Miru cold allowed":"Miru cold blocked";</script>';
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    return response.end(body);
  }
  if (url.pathname === '/' || url.pathname === '/adblock' || url.pathname === '/adblock-default') {
    const useDefaultRule = url.pathname === '/adblock-default';
    const script = useDefaultRule ? '/ads/custom_ads.js' : '/miru-test-ad.js';
    const explanation = useDefaultRule
      ? '<p>此页面引用的 /ads/custom_ads.js 匹配随官方 uBO 1.75.0 分发的 EasyList 通用规则 /ads/custom_ads.js$script。启用时服务器不应收到脚本请求；关闭拦截并刷新后应收到。不能单凭脚本加载失败判断拦截，应同时检查 uBO badge 和 /requests。</p>'
      : `<p>可在 uBO「自定义静态规则」添加下列测试规则，刷新后对比盾牌计数和 <code>/requests</code> 中的脚本请求。此用例验证本地阻断链路，不代表默认过滤列表评分。</p><code>||10.0.2.2:${port}/miru-test-ad.js$script</code>`;
    const body = `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Miru M1 fixture</title><style>body{font:18px system-ui;margin:24px}a{display:block;margin:18px 0}code{overflow-wrap:anywhere}</style><h1>Miru M1 测试</h1><a href="/download?bytes=5242880">下载 5 MB 文件</a><a href="/download?bytes=33554432&delay=40">下载 32 MB 慢速文件</a><a href="/download?bytes=8388608&fail-once=1">下载 8 MB 中断重试文件</a>${explanation}<p id="ad-state">脚本尚未执行</p><script src="${script}"></script><script>document.getElementById('ad-state').textContent=document.documentElement.dataset.miruAdLoaded==='true'?'测试脚本已加载':'测试脚本未加载（需结合 uBO 计数及服务器日志判断）';</script></html>`;
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    return response.end(body);
  }
  if (url.pathname !== '/download') { response.writeHead(404); return response.end(); }
  const size = sizeOf(url);
  const etag = `"miru-fixture-v${etagVersion}-${size}"`;
  const lastModified = new Date(Date.UTC(2026, 9, 20 + etagVersion)).toUTCString();
  const rangeHeader = request.headers.range;
  const range = rangeHeader && /^bytes=(\d+)-(\d*)$/.exec(rangeHeader);
  const honorRange = !url.searchParams.has('ignore-range') && range && (!request.headers['if-range'] || [etag, lastModified].includes(request.headers['if-range']));
  const start = honorRange ? Number(range[1]) : 0;
  const end = honorRange && range[2] ? Math.min(Number(range[2]), size - 1) : size - 1;
  if (start > end || start >= size) { response.writeHead(416, { 'Content-Range': `bytes */${size}` }); return response.end(); }
  const headers = {
    'Content-Type': 'application/octet-stream',
    'Content-Disposition': 'attachment; filename="miru-fixture.bin"; filename*=UTF-8\'\'Miru%20%E6%B5%8B%E8%AF%95.bin',
    'Content-Length': end - start + 1,
    'Accept-Ranges': 'bytes', 'ETag': etag, 'Last-Modified': lastModified,
    'Cache-Control': 'no-store',
  };
  if (honorRange) headers['Content-Range'] = `bytes ${start}-${end}/${size}`;
  response.writeHead(honorRange ? 206 : 200, headers);
  if (request.method === 'HEAD') return response.end();
  const delay = Math.max(0, Math.min(1000, Number(url.searchParams.get('delay') || 0)));
  const faultKey = `${url.pathname}${url.search}:${start}-${end}`;
  const fail = url.searchParams.has('fail-once') && !faults.has(faultKey);
  if (fail) faults.add(faultKey);
  for (let offset = start; offset <= end && !response.destroyed;) {
    const count = Math.min(block.length, end - offset + 1);
    if (!response.write(bytesAt(offset, count))) await new Promise(resolve => {
      const done = () => {
        response.off('drain', done);
        response.off('close', done);
        resolve();
      };
      response.once('drain', done);
      response.once('close', done);
    });
    offset += count;
    if (fail && offset - start >= Math.min(512 * 1024, Math.floor((end - start + 1) / 2))) { response.destroy(); return; }
    if (delay) await new Promise(resolve => setTimeout(resolve, delay));
  }
  if (!response.destroyed) response.end();
});
server.listen(port, '127.0.0.1', () => {
  process.stdout.write(`Miru M1 fixtures: http://127.0.0.1:${port}/\n`);
  process.stdout.write(`5 MiB SHA-256: ${sha256(5 * 1024 * 1024)}\n`);
});
