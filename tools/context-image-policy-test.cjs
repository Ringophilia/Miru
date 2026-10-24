// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
'use strict';

// Run the shipped background scripts. Only browser event delivery and the IPC
// peer are fixtures; policy selection, document binding and routing stay in the
// production source. This does not simulate Gecko, DOM mutation or native ports.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const assets = path.resolve(__dirname, '../browser/engine/src/main/assets/extensions/miru-bridge');
const DOCUMENT = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const SECOND_DOCUMENT = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const PRESS = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const PAGE = 'https://page.test/document?case=one';
const IMAGE = 'https://images.test/image.png';

function fixture() {
  const listeners = new Map();
  const registrations = [];
  const requests = [];
  const state = { tabId: 1, frameId: 0, documentUrl: PAGE, senderUrl: PAGE, topUrl: PAGE, documentNonce: DOCUMENT };
  const event = name => ({ addListener(callback, filter, extra) {
    registrations.push({ name, filter, extra, callback });
    if (!listeners.has(name)) listeners.set(name, []);
    listeners.get(name).push(callback);
  } });
  function emit(name, value, sender) {
    const results = (listeners.get(name) || []).map(callback => callback(value, sender));
    return results.find(result => result !== undefined);
  }
  const nativePort = { onDisconnect: event('nativeDisconnect'), onMessage: event('nativeMessage'), postMessage() {} };
  const browser = {
    runtime: { connectNative() { return nativePort; }, onMessage: event('message') },
    webRequest: {
      onBeforeRequest: event('before'), onBeforeSendHeaders: event('sendHeaders'), onHeadersReceived: event('headers'),
      onResponseStarted: event('responseStarted'),
      onCompleted: event('completed'), onErrorOccurred: event('error'),
    },
    tabs: {
      onRemoved: event('removed'),
      async sendMessage(tabId, message, options) {
        if (message.kind === 'miru-media-changed') return;
        assert.equal(message.kind, 'miru-image-policy-resolve', 'Unexpected production IPC request');
        requests.push({ tabId, message, options });
        return peer(message, options);
      },
    },
  };
  // A plain, matching image with no element/meta override. Other tests replace
  // this IPC peer with explicit replies to exercise validation of bad replies.
  let peer = message => ({ found: typeof message.headerPolicy === 'string', policy: message.headerPolicy,
    documentNonce: message.documentNonce, pressNonce: message.pressNonce, documentUrl: state.documentUrl,
    stage: 'baseline-unavailable' });
  const sandbox = { browser, URL, Map, Set, Promise, setTimeout };
  vm.createContext(sandbox);
  for (const file of ['media-classifier.js', 'background.js']) {
    vm.runInContext(fs.readFileSync(path.join(assets, file), 'utf8'), sandbox, { filename: file });
  }
  const sender = () => ({ tab: { id: state.tabId, url: state.topUrl }, frameId: state.frameId, url: state.senderUrl });
  const message = data => emit('message', data, sender());
  return {
    state, requests, registrations, emit, message,
    setPeer(callback) { peer = callback; },
    before(requestId, type = state.frameId === 0 ? 'main_frame' : 'sub_frame') {
      emit('before', { tabId: state.tabId, frameId: state.frameId, type, requestId });
    },
    headers(requestId, policy, { statusCode = 200, url = state.senderUrl, responseHeaders } = {}) {
      const details = { tabId: state.tabId, frameId: state.frameId, type: state.frameId === 0 ? 'main_frame' : 'sub_frame',
        requestId, url, statusCode, responseHeaders: responseHeaders || (policy === null ? [] : [{ name: 'Referrer-Policy', value: policy }]) };
      emit('headers', details);
      emit('responseStarted', details);
    },
    announce() { message({ kind: 'miru-image-document', documentNonce: state.documentNonce, documentUrl: state.documentUrl }); },
    press({ at = Date.now(), gestureAt = at - 600, url = IMAGE, pressNonce = PRESS } = {}) {
      message({ kind: 'miru-image-press', documentNonce: state.documentNonce, pressNonce, at, gestureAt, url, documentUrl: state.documentUrl });
      return at;
    },
    query(at, url = IMAGE, gestureStartedAtMillis = at - 600) { return message({ kind: 'miru-image-policy-query', url, pressedAtMillis: at, gestureStartedAtMillis }); },
    async pressAndQuery() { const at = this.press(); return this.query(at); },
  };
}

test('announce before the queued HTTP headers retains the no-referrer baseline', async () => {
  const f = fixture();
  f.before('navigation-a'); f.announce(); f.headers('navigation-a', 'no-referrer');
  const result = await f.pressAndQuery();
  assert.equal(result.found, true);
  assert.equal(result.policy, 'no-referrer');
  assert.equal(f.requests.at(-1).message.headerPolicy, 'no-referrer');
});

test('only document navigation setup blocks and returns no network modification', () => {
  const f = fixture();
  const registration = f.registrations.find(r => r.name === 'before');
  assert.deepEqual(Array.from(registration.filter.types).sort(), ['main_frame', 'sub_frame']);
  assert.deepEqual(Array.from(registration.extra), ['blocking']);
  const returned = registration.callback({ tabId: 1, frameId: 0, type: 'main_frame', requestId: 'sync-navigation' });
  assert.equal(Object.keys(returned).length, 0);
});

test('intermediate response headers never supply a final document policy', async () => {
  const f = fixture();
  f.before('final-policy');
  const details = { tabId: 1, frameId: 0, type: 'main_frame', requestId: 'final-policy', url: PAGE, statusCode: 200 };
  f.emit('headers', { ...details, responseHeaders: [{ name: 'Referrer-Policy', value: 'unsafe-url' }] });
  f.announce();
  assert.equal((await f.pressAndQuery()).found, false, 'An intermediate wider policy is not evidence');
  f.emit('responseStarted', { ...details, responseHeaders: [{ name: 'Referrer-Policy', value: 'no-referrer' }] });
  assert.equal((await f.pressAndQuery()).policy, 'no-referrer');
});

test('a same-URL press from an earlier native touch cannot own the current menu', async () => {
  const f = fixture();
  f.before('old-press'); f.headers('old-press', 'unsafe-url'); f.announce();
  const at = f.press();
  assert.equal((await f.query(at + 900, IMAGE, at + 100)).found, false);
  assert.equal(f.requests.length, 0, 'Reject the old frame before asking it for a policy');
});

test('headers before announce retains an observed empty default', async () => {
  const f = fixture();
  f.before('navigation-b'); f.headers('navigation-b', null); f.announce();
  const result = await f.pressAndQuery();
  assert.equal(result.found, true);
  assert.equal(result.policy, '');
  assert.equal(f.requests.at(-1).message.headerPolicy, '');
});

test('SPA navigation keeps its document nonce and observed HTTP policy', async () => {
  const f = fixture();
  f.before('navigation-c'); f.headers('navigation-c', 'strict-origin-when-cross-origin'); f.announce();
  f.state.documentUrl = 'https://page.test/changed?case=two';
  assert.equal((await f.pressAndQuery()).policy, 'strict-origin-when-cross-origin');
});

test('SPA replies use the current document address despite an old sender URL', async () => {
  const f = fixture();
  f.before('navigation-c'); f.headers('navigation-c', 'strict-origin-when-cross-origin'); f.announce();
  f.state.documentUrl = 'https://page.test/changed?case=two';
  const result = await f.pressAndQuery();
  assert.equal(result.documentUrl, f.state.documentUrl);
  assert.notEqual(result.documentUrl, f.state.senderUrl);
});

test('late headers from an earlier request cannot provide the new document baseline', async () => {
  const f = fixture();
  f.before('navigation-old'); f.headers('navigation-old', 'unsafe-url'); f.announce();
  f.before('navigation-new'); f.state.documentNonce = SECOND_DOCUMENT; f.announce();
  f.headers('navigation-old', 'unsafe-url');
  const result = await f.pressAndQuery();
  assert.equal(result.found, false);
  assert.equal(f.requests.at(-1).message.headerPolicy, null);
});

test('the current request can complete a previously unknown document baseline', async () => {
  const f = fixture();
  f.before('navigation-new'); f.state.documentNonce = SECOND_DOCUMENT; f.announce();
  assert.equal((await f.pressAndQuery()).found, false);
  f.headers('navigation-new', 'no-referrer');
  assert.equal((await f.pressAndQuery()).policy, 'no-referrer');
});

test('the last recognized token in all HTTP policy fields supplies the baseline', async () => {
  const f = fixture();
  f.before('navigation-policy-list');
  f.headers('navigation-policy-list', null, { responseHeaders: [
    { name: 'Referrer-Policy', value: 'unsupported, unsafe-url' },
    { name: 'referrer-policy', value: 'NO-REFERRER, unknown' },
  ] });
  f.announce();
  assert.equal((await f.pressAndQuery()).policy, 'no-referrer');
});

test('subframe queries route to the trusted pressed frame and its own document', async () => {
  const f = fixture();
  f.state.frameId = 7; f.state.documentUrl = f.state.senderUrl = 'https://frame.test/embed?case=frame';
  f.before('frame-navigation'); f.headers('frame-navigation', 'same-origin'); f.announce();
  const result = await f.pressAndQuery();
  assert.equal(result.policy, 'same-origin');
  assert.equal(result.documentUrl, f.state.documentUrl);
  assert.equal(f.requests.at(-1).options.frameId, 7);
  assert.equal(f.requests.at(-1).tabId, 1);
});

test('an explicit image override may work without a captured HTTP baseline', async () => {
  const f = fixture();
  f.announce();
  f.setPeer(message => ({ found: true, policy: 'no-referrer', documentUrl: f.state.documentUrl,
    documentNonce: message.documentNonce, pressNonce: message.pressNonce }));
  assert.equal((await f.pressAndQuery()).policy, 'no-referrer');
  assert.equal(f.requests.at(-1).message.headerPolicy, null);
});

test('a reply for a different document or press nonce is rejected', async () => {
  for (const field of ['documentNonce', 'pressNonce']) {
    const f = fixture();
    f.before('nonce-navigation'); f.headers('nonce-navigation', 'unsafe-url'); f.announce();
    f.setPeer(message => ({ found: true, policy: 'unsafe-url', documentUrl: f.state.documentUrl,
      documentNonce: message.documentNonce, pressNonce: message.pressNonce, [field]: 'wrong-nonce' }));
    assert.equal((await f.pressAndQuery()).found, false, field);
  }
});

test('a reply cannot substitute another document or an unknown policy', async () => {
  for (const replacement of [{ documentUrl: 'https://other.test/document' }, { policy: 'unknown' }]) {
    const f = fixture();
    f.before('reply-navigation'); f.headers('reply-navigation', 'unsafe-url'); f.announce();
    f.setPeer(message => ({ found: true, policy: 'unsafe-url', documentUrl: f.state.documentUrl,
      documentNonce: message.documentNonce, pressNonce: message.pressNonce, ...replacement }));
    assert.equal((await f.pressAndQuery()).found, false);
  }
});

test('another trusted press arriving while the target is queried invalidates the reply', async () => {
  const f = fixture();
  f.before('press-navigation'); f.headers('press-navigation', 'unsafe-url'); f.announce();
  f.setPeer(message => {
    f.press({ pressNonce: SECOND_DOCUMENT });
    return { found: true, policy: 'unsafe-url', documentUrl: f.state.documentUrl,
      documentNonce: message.documentNonce, pressNonce: message.pressNonce };
  });
  assert.equal((await f.pressAndQuery()).found, false);
});

test('an expired press, wrong URL, cleared press or closed tab cannot be resolved', async () => {
  const f = fixture();
  f.before('expiry-navigation'); f.headers('expiry-navigation', 'unsafe-url'); f.announce();
  const old = f.press({ at: Date.now() - 300_001 });
  assert.equal((await f.query(old)).found, false);
  const current = f.press();
  assert.equal((await f.query(current, 'https://images.test/other.png')).found, false);
  f.message({ kind: 'miru-image-press-clear', documentNonce: DOCUMENT });
  assert.equal((await f.query(current)).found, false);
  const beforeClose = f.press(); f.emit('removed', 1);
  assert.equal((await f.query(beforeClose)).found, false);
  assert.equal(f.requests.length, 0);
});
