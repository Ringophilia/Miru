// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
'use strict';

// Load the shipped content script. This fixture supplies DOM objects, mutation
// records and IPC delivery; it does not implement native meta policy processing.
const assert = require('node:assert/strict');
const crypto = require('node:crypto').webcrypto;
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const source = fs.readFileSync(path.resolve(__dirname,
  '../browser/engine/src/main/assets/extensions/miru-bridge/image-policy.js'), 'utf8');
const PAGE = 'https://page.test/document?private=meta-secret';
const IMAGE = 'https://images.test/image.png';

function fixture(initial = []) {
  const messages = [], events = new Map();
  let receive, observer;
  class Element {
    constructor() { this.children = []; this.parent = null; this.isConnected = false; }
    connect(value) { this.isConnected = value; for (const child of this.children) child.connect(value); }
    append(child) {
      if (child.parent) child.remove();
      this.children.push(child); child.parent = this; child.connect(this.isConnected);
      if (this.isConnected) observer?.enqueue({ type: 'childList', addedNodes: [child], removedNodes: [] });
    }
    remove() {
      if (!this.parent) return;
      const parent = this.parent;
      parent.children.splice(parent.children.indexOf(this), 1); this.parent = null; this.connect(false);
      if (parent.isConnected) observer?.enqueue({ type: 'childList', addedNodes: [], removedNodes: [this] });
    }
    querySelectorAll(selector) {
      assert.equal(selector, 'meta');
      return this.children.flatMap(child => [
        ...(child instanceof HTMLMetaElement ? [child] : []), ...child.querySelectorAll(selector),
      ]);
    }
  }
  class HTMLMetaElement extends Element {
    constructor(name = 'referrer', content = '') { super(); this.values = { name, content }; }
    get name() { return this.values.name; }
    set name(value) { this.setAttribute('name', value); }
    get content() { return this.values.content; }
    set content(value) { this.setAttribute('content', value); }
    setAttribute(name, value) {
      const oldValue = this.values[name]; this.values[name] = String(value);
      if (this.isConnected) observer?.enqueue({ type: 'attributes', target: this, attributeName: name, oldValue });
    }
  }
  class HTMLImageElement extends Element {
    constructor() { super(); this.currentSrc = IMAGE; this.src = IMAGE; this.referrerPolicy = ''; }
  }
  class MutationObserver {
    constructor(callback) { this.callback = callback; this.queue = []; observer = this; }
    observe(target, options) {
      assert.equal(target, document);
      assert.equal(options.attributeOldValue, true, 'Production must request the previous attribute value');
    }
    enqueue(record) { this.queue.push(record); }
    takeRecords() { return this.queue.splice(0); }
  }
  const root = new Element(); root.connect(true);
  const document = {
    URL: PAGE,
    querySelectorAll: selector => root.querySelectorAll(selector),
    addEventListener(kind, listener) { events.set(kind, listener); },
  };
  const metas = initial.map(value => {
    const meta = new HTMLMetaElement(value.name || 'referrer', value.content);
    root.append(meta); return meta;
  });
  const image = new HTMLImageElement(); root.append(image);
  const browser = { runtime: {
    sendMessage(message) { messages.push(message); return Promise.resolve(); },
    onMessage: { addListener(listener) { receive = listener; } },
  } };
  const sandbox = { browser, document, crypto, Uint8Array, HTMLMetaElement, HTMLImageElement,
    MutationObserver, WeakRef, window: { addEventListener() {} } };
  vm.createContext(sandbox);
  vm.runInContext(source, sandbox, { filename: 'image-policy.js' });
  function press() {
    events.get('pointerdown')({ type: 'pointerdown', isTrusted: true, composedPath: () => [image] });
    return messages.filter(message => message.kind === 'miru-image-press').at(-1);
  }
  press();
  return {
    root, metas, image,
    meta(content, name = 'referrer') { return new HTMLMetaElement(name, content); },
    container() { return new Element(); },
    flush() { const records = observer.takeRecords(); if (records.length) observer.callback(records); },
    resolve(headerPolicy = 'unsafe-url', replacement = {}) {
      const identity = messages.filter(message => message.kind === 'miru-image-press').at(-1);
      return receive({ kind: 'miru-image-policy-resolve', documentNonce: identity.documentNonce,
        pressNonce: identity.pressNonce, url: IMAGE, headerPolicy, ...replacement });
    },
  };
}

async function expectPolicy(f, policy, baseline = 'unsafe-url') {
  const result = await f.resolve(baseline);
  assert.equal(result.found, true);
  assert.equal(result.policy, policy);
  assert.equal(result.documentUrl, PAGE);
}
async function expectUnavailable(f, stage) {
  const result = await f.resolve();
  assert.equal(result.found, false, `Expected ${stage}, got ${JSON.stringify(result)}`);
  assert.equal(result.stage, stage);
}

test('an observed empty HTTP policy remains available without meta', async () => {
  await expectPolicy(fixture(), '', '');
});

test('an unknown HTTP baseline is unavailable without a live override', async () => {
  const result = await fixture().resolve(null);
  assert.equal(result.found, false);
  assert.equal(result.stage, 'baseline-unavailable');
});

test('an initially connected referrer meta overrides the HTTP policy', async () => {
  await expectPolicy(fixture([{ content: 'no-referrer' }]), 'no-referrer');
});

test('a complete newly inserted meta supplies its visible policy', async () => {
  const f = fixture(); f.root.append(f.meta('no-referrer'));
  await expectPolicy(f, 'no-referrer');
});

for (const delivery of ['queued until resolve', 'observer callback']) {
  test(`an inserted valid meta followed by one invalid content write is unavailable: ${delivery}`, async () => {
    const f = fixture(), meta = f.meta('no-referrer');
    f.root.append(meta); meta.content = 'invalid';
    if (delivery === 'observer callback') f.flush();
    await expectUnavailable(f, 'meta-ambiguous');
  });
}

test('the insertion check includes a meta inside an added subtree', async () => {
  const f = fixture(), container = f.container(), meta = f.meta('no-referrer');
  container.append(meta); f.root.append(container); meta.content = 'invalid';
  await expectUnavailable(f, 'meta-ambiguous');
});

test('a previously processed node reinserted with an unseen policy cannot reuse stale evidence', async () => {
  const f = fixture([{ content: 'unsafe-url' }]), meta = f.metas[0];
  meta.remove(); f.flush();
  meta.content = 'no-referrer';
  f.root.append(meta); meta.content = 'invalid';
  await expectUnavailable(f, 'meta-ambiguous');
});

test('one visible content write on an existing meta supplies the new policy', async () => {
  const f = fixture([{ content: 'unsafe-url' }]); f.metas[0].content = 'no-referrer';
  await expectPolicy(f, 'no-referrer');
});

test('one invalid content write on an already observed meta retains its known policy', async () => {
  const f = fixture([{ content: 'no-referrer' }]); f.metas[0].content = 'invalid';
  await expectPolicy(f, 'no-referrer');
});

test('multiple content writes on an existing meta cannot fall back to HTTP', async () => {
  const f = fixture([{ content: 'unsafe-url' }]);
  f.metas[0].content = 'no-referrer'; f.metas[0].content = 'invalid';
  await expectUnavailable(f, 'meta-ambiguous');
});

test('multiple content writes also reject a previously unprocessed meta', async () => {
  const f = fixture([{ content: 'invalid' }]);
  f.metas[0].content = 'no-referrer'; f.metas[0].content = 'invalid';
  await expectUnavailable(f, 'meta-ambiguous');
});

test('adding and removing an unseen referrer meta is unavailable', async () => {
  const f = fixture(), meta = f.meta('no-referrer'); f.root.append(meta); meta.remove();
  await expectUnavailable(f, 'meta-ambiguous');
});

test('adding and removing a subtree containing an unseen referrer meta is unavailable', async () => {
  const f = fixture(), container = f.container(); container.append(f.meta('no-referrer'));
  f.root.append(container); container.remove();
  await expectUnavailable(f, 'meta-ambiguous');
});

test('removing an already observed meta retains its processed policy', async () => {
  const f = fixture([{ content: 'no-referrer' }]); f.metas[0].remove();
  await expectPolicy(f, 'no-referrer');
});

test('changing an observed referrer meta name does not invent the current document policy', async () => {
  const f = fixture([{ content: 'no-referrer' }]); f.metas[0].name = 'other';
  await expectUnavailable(f, 'meta-ambiguous');
});

test('changing a new referrer meta name before observation is unavailable', async () => {
  const f = fixture(), meta = f.meta('no-referrer'); f.root.append(meta); meta.name = 'other';
  await expectUnavailable(f, 'meta-ambiguous');
});

test('one visible name change can activate an existing meta', async () => {
  const f = fixture([{ name: 'other', content: 'no-referrer' }]); f.metas[0].name = 'referrer';
  await expectPolicy(f, 'no-referrer');
});

for (const policy of ['no-referrer', 'unsafe-url']) {
  test(`an explicit live image ${policy} override works despite ambiguous meta evidence`, async () => {
    const f = fixture(), meta = f.meta('no-referrer');
    f.root.append(meta); meta.content = 'invalid'; f.image.referrerPolicy = policy;
    await expectPolicy(f, policy);
  });
}

test('a live image override still requires the exact press identity', async () => {
  const f = fixture(); f.image.referrerPolicy = 'no-referrer';
  const result = await f.resolve('unsafe-url', { pressNonce: 'wrong-press' });
  assert.equal(result.found, false);
  assert.equal(result.stage, 'target-mismatch');
});
