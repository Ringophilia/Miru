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
// Current-document policy and trusted image press identity. Process memory only.
const imageDocuments = new Map(), imagePresses = new Map(), imageNavigations = new Map();
const referrerPolicies = new Set(['no-referrer', 'no-referrer-when-downgrade', 'same-origin', 'origin', 'strict-origin', 'origin-when-cross-origin', 'strict-origin-when-cross-origin', 'unsafe-url']);
const documentKey = (tabId, frameId) => `${tabId}:${frameId}`;
const withoutFragment = value => String(value || '').split('#')[0];
function sameDocumentOrigin(left, right) {
  try {
    const a = new URL(left), b = new URL(right);
    return withoutFragment(left) === withoutFragment(right) || (['http:', 'https:'].includes(a.protocol) && a.origin === b.origin);
  } catch (_) { return false; }
}
function responsePolicy(headers) {
  const values = headers.filter(header => header.name.toLowerCase() === 'referrer-policy').map(header => header.value || '').join(',');
  return values.split(',').map(value => value.trim().toLowerCase()).filter(value => referrerPolicies.has(value)).at(-1) || '';
}
function publish(tabId) {
  browser.tabs.sendMessage(tabId, { kind: 'miru-media-changed' }).catch(() => {});
}
browser.webRequest.onBeforeRequest.addListener(details => {
  if (details.type === 'main_frame' || details.type === 'sub_frame') imageNavigations.set(documentKey(details.tabId, details.frameId), details.requestId);
  if (imageNavigations.size > 1_024) imageNavigations.delete(imageNavigations.keys().next().value);
  if (details.type === 'main_frame') {
    generations.set(details.tabId, (generations.get(details.tabId) || 0) + 1);
    mediaTabs.delete(details.tabId); suppressedTabs.delete(details.tabId); publish(details.tabId);
    for (const [key, value] of imageDocuments) if (value.tabId === details.tabId) imageDocuments.delete(key);
    imagePresses.delete(details.tabId);
  } else if (details.type === 'sub_frame') {
    imageDocuments.delete(documentKey(details.tabId, details.frameId));
    if (imagePresses.get(details.tabId)?.frameId === details.frameId) imagePresses.delete(details.tabId);
  }
}, { urls: ['<all_urls>'] });
browser.webRequest.onBeforeSendHeaders.addListener(details => {
  if (details.tabId < 0) return;
  requestHeaders.set(details.requestId, { tabId: details.tabId, generation: generations.get(details.tabId) || 0, headers: MiruMedia.headers(details.requestHeaders) });
  if (requestHeaders.size > 512) requestHeaders.delete(requestHeaders.keys().next().value);
}, { urls: ['<all_urls>'] }, ['requestHeaders']);
browser.webRequest.onHeadersReceived.addListener(details => {
  if (details.tabId < 0) return;
  if (details.type === 'main_frame' || details.type === 'sub_frame') {
    const key = documentKey(details.tabId, details.frameId), previous = imageDocuments.get(key);
    if (imageNavigations.get(key) === details.requestId) {
      // document_start messaging can reach this background before the queued
      // headers event. Merge its nonce; replacing the record would lose the
      // identity of an otherwise known, valid HTTP policy (including empty).
      const documentNonce = withoutFragment(previous?.url) === withoutFragment(details.url) ? previous?.documentNonce : undefined;
      imageDocuments.set(key, { tabId: details.tabId, url: details.url, policy: responsePolicy(details.responseHeaders || []), documentNonce });
    }
    if (imageDocuments.size > 512) imageDocuments.delete(imageDocuments.keys().next().value);
  }
  if (details.statusCode >= 400) return;
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
  imagePresses.delete(id);
  for (const [key, value] of imageDocuments) if (value.tabId === id) imageDocuments.delete(key);
  for (const key of imageNavigations.keys()) if (key.startsWith(`${id}:`)) imageNavigations.delete(key);
});
browser.runtime.onMessage.addListener((message, sender) => {
  if (message.kind === 'miru-image-document' && sender.tab && sender.frameId >= 0 &&
      /^[0-9a-f-]{36}$/.test(message.documentNonce) && typeof message.documentUrl === 'string' &&
      message.documentUrl.length <= 65_536 && sameDocumentOrigin(message.documentUrl, sender.url)) {
    const key = documentKey(sender.tab.id, sender.frameId), previous = imageDocuments.get(key);
    const policy = previous?.documentNonce === message.documentNonce || withoutFragment(previous?.url) === withoutFragment(sender.url) ? previous?.policy : null;
    imageDocuments.set(key, { tabId: sender.tab.id, url: message.documentUrl, policy: typeof policy === 'string' ? policy : null, documentNonce: message.documentNonce });
    if (imageDocuments.size > 512) imageDocuments.delete(imageDocuments.keys().next().value);
    return;
  }
  if (message.kind === 'miru-image-press' && sender.tab && sender.frameId >= 0 &&
      typeof message.url === 'string' && message.url.length <= 2_000_000 && Number.isSafeInteger(message.at) && Number.isSafeInteger(message.gestureAt) &&
      /^[0-9a-f-]{36}$/.test(message.documentNonce) && /^[0-9a-f-]{36}$/.test(message.pressNonce) &&
      typeof message.documentUrl === 'string' && message.documentUrl.length <= 65_536 && sameDocumentOrigin(message.documentUrl, sender.url)) {
    imagePresses.set(sender.tab.id, { frameId: sender.frameId, documentUrl: message.documentUrl,
      documentNonce: message.documentNonce, pressNonce: message.pressNonce, at: message.at, gestureAt: message.gestureAt, url: message.url });
    if (imagePresses.size > 128) imagePresses.delete(imagePresses.keys().next().value);
    return;
  }
  if (message.kind === 'miru-image-press-clear' && sender.tab) {
    imagePresses.delete(sender.tab.id); return;
  }
  if (message.kind === 'miru-image-policy-query' && sender.tab) return (async () => {
    const press = imagePresses.get(sender.tab.id);
    if (!press || press.url !== message.url || !Number.isSafeInteger(message.pressedAtMillis) ||
        !Number.isSafeInteger(message.gestureStartedAtMillis) || message.gestureStartedAtMillis < 0 ||
        press.gestureAt < message.gestureStartedAtMillis - 2 || press.gestureAt > message.pressedAtMillis + 2_000 ||
        Math.abs(press.at - message.pressedAtMillis) > 2_000 || Date.now() - press.at > 300_000) return { found: false, stage: 'press-identity' };
    const baseline = imageDocuments.get(documentKey(sender.tab.id, press.frameId));
    const headerPolicy = baseline?.documentNonce === press.documentNonce ? baseline.policy : null;
    const result = await browser.tabs.sendMessage(sender.tab.id, { kind: 'miru-image-policy-resolve',
      documentNonce: press.documentNonce, pressNonce: press.pressNonce, url: press.url, headerPolicy }, { frameId: press.frameId }).catch(() => null);
    if (imagePresses.get(sender.tab.id) !== press || !result?.found || result.documentNonce !== press.documentNonce ||
        result.pressNonce !== press.pressNonce || withoutFragment(result.documentUrl) !== withoutFragment(press.documentUrl) ||
        typeof result.policy !== 'string' || (result.policy !== '' && !referrerPolicies.has(result.policy))) return { found: false,
          stage: ['target-mismatch', 'meta-ambiguous', 'baseline-unavailable'].includes(result?.stage) ? result.stage : 'target-invalid' };
    return { found: true, documentUrl: result.documentUrl, policy: result.policy, stage: 'policy-live' };
  })();
  if (message.kind !== 'miru-media-query' || !sender.tab) return;
  return Promise.resolve({ frameId: sender.frameId, pageUrl: sender.tab.url, suppressed: [...(suppressedTabs.get(sender.tab.id) || [])], items: [...(mediaTabs.get(sender.tab.id)?.values() || [])].filter(item => item.frameId === sender.frameId) });
});
