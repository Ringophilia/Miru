// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
(() => {
  let nativePort, relayNonce, stopped = false, timer, networks = [], pageUrl = '', paused, revision = 0;
  let holdSettings = { holdEnabled: true, holdSpeed: 2 };
  const webHold = MiruWebHold.install(document, window);
  webHold.configure(holdSettings);
  const elements = () => [...document.querySelectorAll('video,audio')];
  const finite = value => Number.isFinite(value) && value >= 0 ? value : 0;
  const source = element => element.currentSrc || element.src || '';
  function match(item) {
    const list = elements();
    return list.find(e => MiruMedia.key(source(e)) === item.id) || (list.length === 1 && source(list[0]).startsWith('blob:') ? list[0] : null);
  }
  async function report(event = '') {
    if (!nativePort || stopped) return;
    const current = ++revision;
    const response = await browser.runtime.sendMessage({ kind: 'miru-media-query' }).catch(() => null);
    if (!response || stopped || current !== revision) return;
    networks = response.items; pageUrl = response.pageUrl;
    if (globalThis.MiruImagePolicy) try {
      nativePort.postMessage({ kind: 'image-relay', pageUrl: window === window.top ? document.URL : pageUrl, documentUrl: document.URL,
        documentNonce: MiruImagePolicy.documentNonce, relayNonce });
    } catch (_) {}
    const items = new Map(networks.map(item => [item.id, { ...item }]));
    for (const element of elements()) {
      const url = source(element), id = MiruMedia.key(url), kind = MiruMedia.classify(url);
      if (response.suppressed?.includes(id)) continue;
      if (kind && !items.has(id)) items.set(id, { id, url, kind, headers: {} });
      if (url.startsWith('blob:') && !networks.length) items.set(url, { id: url, url, kind: 'MSE', headers: {} });
    }
    for (const item of items.values()) {
      const element = match(item);
      item.title = document.title;
      if (element) Object.assign(item, { duration: finite(element.duration), position: finite(element.currentTime), width: element.videoWidth || 0, height: element.videoHeight || 0, playing: !element.paused && !element.ended, drm: !!element.mediaKeys || element.dataset.miruEncrypted === 'true' });
    }
    try { nativePort.postMessage({ kind: 'media', pageUrl, frameId: response.frameId, event, items: [...items.values()] }); } catch (_) {}
  }
  function connectMedia() {
    if (stopped || nativePort) return;
    const connection = browser.runtime.connectNative('miru_media');
    relayNonce = globalThis.MiruImagePolicy?.newNonce() || '';
    const connectionNonce = relayNonce;
    nativePort = connection;
    connection.onDisconnect.addListener(() => {
      if (nativePort !== connection) return;
      nativePort = null; webHold.cancel();
      if (!stopped) setTimeout(connectMedia, 500);
    });
    connection.onMessage.addListener(message => {
      if (nativePort !== connection) return;
      if (message.kind === 'media-settings') {
        holdSettings = { holdEnabled: message.holdEnabled !== false, holdSpeed: Number.isFinite(message.holdSpeed) ? message.holdSpeed : 2 };
        webHold.configure(holdSettings);
      } else if (message.kind === 'query-image-policy') {
        if (message.relayNonce !== connectionNonce || message.documentNonce !== globalThis.MiruImagePolicy?.documentNonce ||
            typeof message.id !== 'string' || message.id.length !== 36) return;
        browser.runtime.sendMessage({ kind: 'miru-image-policy-query', url: message.url, pressedAtMillis: message.pressedAtMillis,
          gestureStartedAtMillis: message.gestureStartedAtMillis })
          .then(result => {
            if (!stopped && nativePort === connection) connection.postMessage({ kind: 'image-policy', id: message.id,
              relayNonce: connectionNonce, documentNonce: MiruImagePolicy.documentNonce, result });
          }).catch(() => {
            if (!stopped && nativePort === connection) try { connection.postMessage({ kind: 'image-policy', id: message.id,
              relayNonce: connectionNonce, documentNonce: MiruImagePolicy.documentNonce, result: { found: false } }); } catch (_) {}
          });
      } else if (message.kind === 'prepare') {
        webHold.cancel();
        const item = networks.find(item => item.id === message.mediaId) || { id: message.mediaId };
        const element = match(item);
        const drm = !!element && (!!element.mediaKeys || element.dataset.miruEncrypted === 'true');
        const wasPlaying = !!element && !element.paused;
        const position = element ? finite(element.currentTime) : 0;
        if (!drm && element) { paused = { element, wasPlaying }; element.pause(); }
        connection.postMessage({ kind: 'prepared', id: message.id, position, drm, wasPlaying });
      } else if (message.kind === 'restore') {
        if (paused?.wasPlaying && paused.element.isConnected) paused.element.play().catch(() => {});
        paused = null;
      }
    });
    report();
  }
  browser.runtime.onMessage.addListener(message => { if (message.kind === 'miru-media-changed') report(); });
  for (const event of ['loadedmetadata', 'durationchange', 'play', 'pause', 'emptied', 'encrypted']) document.addEventListener(event, e => {
    if (!(e.target instanceof HTMLMediaElement)) return;
    if (event === 'encrypted') e.target.dataset.miruEncrypted = 'true';
    report(event);
  }, true);
  document.addEventListener('fullscreenchange', () => report('fullscreen'));
  const observer = new MutationObserver(() => { clearTimeout(timer); timer = setTimeout(report, 150); });
  observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
  let interval = setInterval(report, 2_000);
  window.addEventListener('pagehide', () => { stopped = true; revision++; webHold.stop(); clearInterval(interval); clearTimeout(timer); observer.disconnect(); nativePort?.disconnect(); nativePort = null; });
  window.addEventListener('pageshow', e => {
    if (!e.persisted) return;
    stopped = false;
    observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
    interval = setInterval(report, 2_000);
    webHold.configure(holdSettings);
    connectMedia();
  });
  connectMedia();
})();
