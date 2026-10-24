// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
// document_start: observe meta processing before it can be removed or changed.
(() => {
  const policies = new Set(['no-referrer', 'no-referrer-when-downgrade', 'same-origin', 'origin', 'strict-origin', 'origin-when-cross-origin', 'strict-origin-when-cross-origin', 'unsafe-url']);
  const legacy = { never: 'no-referrer', always: 'unsafe-url', default: 'no-referrer-when-downgrade', 'origin-when-crossorigin': 'origin-when-cross-origin' };
  function nonce() {
    // randomUUID is SecureContext-only; ordinary HTTP image pages still need
    // this capability. getRandomValues remains available in those documents.
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
    const hex = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  const documentNonce = nonce();
  let metaPolicy = null, lastPress = null, metaEvidenceLost = false;
  const processedMetas = new WeakSet();
  function readMeta(node) {
    if (!(node instanceof HTMLMetaElement) || !node.isConnected || node.name.toLowerCase() !== 'referrer') return;
    const value = node.content.trim().toLowerCase();
    if (policies.has(value)) { metaPolicy = value; processedMetas.add(node); }
    else if (Object.hasOwn(legacy, value)) { metaPolicy = legacy[value]; processedMetas.add(node); }
  }
  function records(changes) {
    const changedMetas = new Map(), insertedMetas = new WeakSet();
    for (const change of changes) if (change.type === 'childList') {
      for (const node of change.addedNodes) {
        if (node instanceof HTMLMetaElement) insertedMetas.add(node);
        node.querySelectorAll?.('meta').forEach(meta => insertedMetas.add(meta));
      }
    }
    for (const change of changes) if (change.type === 'attributes' && change.target instanceof HTMLMetaElement) {
      if (change.target.name.toLowerCase() !== 'referrer' && !processedMetas.has(change.target) &&
          !(change.attributeName === 'name' && change.oldValue?.toLowerCase() === 'referrer')) continue;
      const count = (changedMetas.get(change.target) || 0) + 1;
      changedMetas.set(change.target, count);
      // Two same-task writes, or insertion followed by one write, can hide a
      // valid intermediate policy. The final node cannot prove its earlier value.
      if (count > 1 || insertedMetas.has(change.target) || !change.target.isConnected || change.target.name.toLowerCase() !== 'referrer') metaEvidenceLost = true;
    }
    for (const change of changes) {
      if (change.type === 'attributes') readMeta(change.target);
      else {
        for (const node of change.addedNodes) {
          if (node instanceof HTMLMetaElement && node.name.toLowerCase() === 'referrer' && !node.isConnected) metaEvidenceLost = true;
          readMeta(node);
          node.querySelectorAll?.('meta').forEach(readMeta);
        }
        for (const node of change.removedNodes) {
          const removed = node instanceof HTMLMetaElement ? [node] : [...(node.querySelectorAll?.('meta') || [])];
          if (removed.some(meta => meta.name.toLowerCase() === 'referrer' && !processedMetas.has(meta))) metaEvidenceLost = true;
        }
      }
    }
  }
  document.querySelectorAll('meta').forEach(readMeta);
  const observer = new MutationObserver(records);
  observer.observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['name', 'content'], attributeOldValue: true });
  function announce() { browser.runtime.sendMessage({ kind: 'miru-image-document', documentNonce, documentUrl: document.URL }).catch(() => {}); }
  announce();
  function press(event) {
    if (!event.isTrusted) return;
    const image = event.composedPath().find(node => node instanceof HTMLImageElement);
    if (!image) {
      lastPress = null;
      browser.runtime.sendMessage({ kind: 'miru-image-press-clear', documentNonce }).catch(() => {});
      return;
    }
    const url = image.currentSrc || image.src;
    if (!url) return;
    const at = Date.now(), pressNonce = nonce();
    // Keep the original pointer-down time when Gecko later emits contextmenu.
    // A previous frame's press cannot satisfy a newer native touch just because
    // both elements have the same URL and the menu events occur close together.
    const gestureAt = event.type === 'pointerdown' ? at :
      (lastPress?.image.deref() === image && lastPress.url === url ? lastPress.gestureAt : null);
    if (!Number.isSafeInteger(gestureAt)) return;
    lastPress = { image: new WeakRef(image), at, gestureAt, pressNonce, url };
    browser.runtime.sendMessage({ kind: 'miru-image-press', documentNonce, pressNonce, at, gestureAt, url, documentUrl: document.URL }).catch(() => {});
  }
  document.addEventListener('pointerdown', press, { capture: true, passive: true });
  document.addEventListener('contextmenu', press, { capture: true, passive: true });
  browser.runtime.onMessage.addListener(message => {
    if (message.kind !== 'miru-image-policy-resolve') return;
    records(observer.takeRecords());
    const image = lastPress?.image.deref();
    if (message.documentNonce !== documentNonce || message.pressNonce !== lastPress?.pressNonce || !image?.isConnected ||
        message.url !== lastPress.url || message.url !== (image.currentSrc || image.src)) return Promise.resolve({ found: false, stage: 'target-mismatch' });
    // Gecko's document.referrerPolicy is ChromeOnly. The background observed the
    // HTTP response baseline; document_start meta processing and the exact image
    // override it. Missing baseline evidence is never changed into a default.
    if (metaEvidenceLost && !image.referrerPolicy) return Promise.resolve({ found: false, stage: 'meta-ambiguous' });
    const policy = image.referrerPolicy || metaPolicy || message.headerPolicy;
    if (typeof policy !== 'string' || (policy !== '' && !policies.has(policy))) return Promise.resolve({ found: false, stage: 'baseline-unavailable' });
    return Promise.resolve({ found: true, policy, documentNonce, pressNonce: lastPress.pressNonce, documentUrl: document.URL });
  });
  globalThis.MiruImagePolicy = { documentNonce, newNonce: nonce };
  window.addEventListener('pagehide', () => { lastPress = null; });
})();
