// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
// Only native code can send commands on this port. Credentials never enter extension storage.
let port;
function connect() {
  port = browser.runtime.connectNative('miru');
  port.onDisconnect.addListener(() => setTimeout(connect, 500));
  port.onMessage.addListener(async message => {
    if (message.kind !== 'cookies' || typeof message.id !== 'string') return;
    try {
      const url = new URL(message.url);
      if (!['http:', 'https:'].includes(url.protocol)) throw new Error('Unsupported URL');
      const storeId = message.private ? 'firefox-private' : 'firefox-default';
      const cookies = await browser.cookies.getAll({ url: url.href, storeId });
      // RFC 6265: longest matching path first; do not log or persist the resulting header.
      cookies.sort((a, b) => b.path.length - a.path.length);
      port.postMessage({ kind: 'cookies', id: message.id, cookie: cookies.map(cookie => `${cookie.name}=${cookie.value}`).join('; ') });
    } catch (_) {
      port.postMessage({ kind: 'cookies', id: message.id, error: true });
    }
  });
}
connect();

// Bounded process memory only. Native content ports provide the trusted EngineSession association.
const mediaTabs = new Map();
const suppressedTabs = new Map();
const generations = new Map();
const requestHeaders = new Map();
function publish(tabId) {
  browser.tabs.sendMessage(tabId, { kind: 'miru-media-changed' }).catch(() => {});
}
browser.webRequest.onBeforeRequest.addListener(details => {
  if (details.type === 'main_frame') {
    generations.set(details.tabId, (generations.get(details.tabId) || 0) + 1);
    mediaTabs.delete(details.tabId); suppressedTabs.delete(details.tabId); publish(details.tabId);
  }
}, { urls: ['<all_urls>'] });
browser.webRequest.onBeforeSendHeaders.addListener(details => {
  if (details.tabId < 0) return;
  requestHeaders.set(details.requestId, { tabId: details.tabId, generation: generations.get(details.tabId) || 0, headers: MiruMedia.headers(details.requestHeaders) });
  if (requestHeaders.size > 512) requestHeaders.delete(requestHeaders.keys().next().value);
}, { urls: ['<all_urls>'] }, ['requestHeaders']);
browser.webRequest.onHeadersReceived.addListener(details => {
  if (details.tabId < 0 || details.statusCode >= 400) return;
  const captured = requestHeaders.get(details.requestId);
  if (!captured || captured.generation !== (generations.get(details.tabId) || 0)) return;
  const values = Object.fromEntries((details.responseHeaders || []).map(h => [h.name.toLowerCase(), h.value]));
  const total = Number((values['content-range'] || '').split('/')[1]) || Number(values['content-length']) || 0;
  const kind = MiruMedia.classify(details.url, values['content-type'], total);
  if (!kind && (values['content-type'] || '').toLowerCase().startsWith('video/') && total > 0 && total < 200 * 1024) {
    const suppressed = suppressedTabs.get(details.tabId) || new Set();
    suppressed.add(MiruMedia.key(details.url));
    if (suppressed.size > 64) suppressed.delete(suppressed.values().next().value);
    suppressedTabs.set(details.tabId, suppressed);
    mediaTabs.get(details.tabId)?.delete(MiruMedia.key(details.url));
    publish(details.tabId);
  }
  if (!kind) return;
  const items = mediaTabs.get(details.tabId) || new Map();
  items.set(MiruMedia.key(details.url), { id: MiruMedia.key(details.url), url: details.url, kind, mimeType: values['content-type'] || '', headers: captured.headers, frameId: details.frameId });
  if (items.size > 64) items.delete(items.keys().next().value);
  mediaTabs.set(details.tabId, items);
  publish(details.tabId);
}, { urls: ['<all_urls>'] }, ['responseHeaders']);
function forget(details) { requestHeaders.delete(details.requestId); }
browser.webRequest.onCompleted.addListener(forget, { urls: ['<all_urls>'] });
browser.webRequest.onErrorOccurred.addListener(forget, { urls: ['<all_urls>'] });
browser.tabs.onRemoved.addListener(id => {
  mediaTabs.delete(id); suppressedTabs.delete(id); generations.delete(id);
  for (const [requestId, captured] of requestHeaders) if (captured.tabId === id) requestHeaders.delete(requestId);
});
browser.runtime.onMessage.addListener((message, sender) => {
  if (message.kind !== 'miru-media-query' || !sender.tab) return;
  return Promise.resolve({ frameId: sender.frameId, pageUrl: sender.tab.url, suppressed: [...(suppressedTabs.get(sender.tab.id) || [])], items: [...(mediaTabs.get(sender.tab.id)?.values() || [])].filter(item => item.frameId === sender.frameId) });
});
