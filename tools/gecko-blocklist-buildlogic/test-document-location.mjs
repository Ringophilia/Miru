// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
// Executes the actual pinned navigation modules with local services; no Gecko/device/network.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import vm from "node:vm";

const directory = resolve(process.argv[2] ?? "third_party/mozilla-permission-policy");
const original = await readFile(resolve(directory, "upstream/GeckoViewNavigation.sys.mjs"), "utf8");
const patched = await readFile(resolve(directory, "GeckoViewNavigation.sys.mjs"), "utf8");
const importLine = 'import { GeckoViewModule } from "resource://gre/modules/GeckoViewModule.sys.mjs";';
const exportLine = "export class GeckoViewNavigation";
const SAME_DOCUMENT = 1;
const normalize = value => JSON.parse(JSON.stringify(value));

function exercise(source, { calls, principal = true, supported = true, privateMode = false,
  gesture = null, exposeThrows = false, permissionsThrow = false, initialBlank = false }) {
  const messages = [], timeline = [], warnings = [];
  const context = {
    __Module: class { static initLogging() { return { debug() {}, warn: (...args) => warnings.push(args) }; } },
    ChromeUtils: {
      defineESModuleGetters(target) {
        target.GeckoViewUtils = { isSupportedPermissionsPrincipal: () => supported };
        target.E10SUtils = { serializePrincipal: () => "synthetic-principal" };
        target.TranslationsParent = { onLocationChange: (_window, uri, progress) => timeline.push(["translation", uri.spec, progress.isTopLevel]) };
      },
      defineLazyGetter() {},
    },
    Ci: {
      nsIWebProgressListener: { LOCATION_CHANGE_SAME_DOCUMENT: SAME_DOCUMENT },
      nsIPermissionManager: { DENY_ACTION: 2 },
    },
    Services: {
      io: { createExposableURI(uri) { if (exposeThrows && uri.spec) throw new Error("synthetic exposure failure"); return uri; } },
      perms: { getAllForPrincipal() { if (permissionsThrow) throw new Error("synthetic permission lookup failure"); return [{ type: "camera", capability: 3, principal: contentPrincipal }]; } },
    },
  };
  const contentPrincipal = {
    URI: { displaySpec: "https://fixture.invalid/" }, originAttributes: { geckoViewSessionContextId: "synthetic-context" },
    privateBrowsingId: privateMode ? 1 : 0, isContentPrincipal: true,
  };
  assert.equal(source.split(importLine).length, 2);
  assert.equal(source.split(exportLine).length, 2);
  vm.runInNewContext(source.replace(importLine, "const GeckoViewModule = globalThis.__Module;")
    .replace(exportLine, "class GeckoViewNavigation") + "\nglobalThis.Navigation = GeckoViewNavigation;", context);
  const navigation = new context.Navigation();
  navigation.browser = { contentPrincipal: principal ? contentPrincipal : null, canGoBack: true, canGoForward: false };
  navigation.window = { document: { hasValidTransientUserGestureActivation: gesture } };
  navigation.eventDispatcher = {
    // This is the fixed Messaging.sys.mjs sendRequest(aType,aMsg,aCallback) contract.
    sendRequest(type, payload) {
      assert.equal(typeof type, "string");
      const message = normalize({ type, payload });
      messages.push(message); timeline.push(message);
    },
  };
  navigation._initialAboutBlank = initialBlank;
  for (const call of calls) {
    navigation.onLocationChange({ isTopLevel: call.top }, {}, { spec: call.uri, displaySpec: call.uri }, call.flags);
  }
  return normalize({ messages, timeline, warnings, initialBlank: navigation._initialAboutBlank });
}

let checks = 0;
function compare(options) {
  const before = exercise(original, options), after = exercise(patched, options);
  const notifications = after.messages.filter(message => message.type === "Miru:DocumentLocation");
  assert.deepEqual(after.messages.filter(message => message.type !== "Miru:DocumentLocation"), before.messages,
    "Original native message payloads/order changed");
  assert.deepEqual(after.timeline.filter(item => item.type !== "Miru:DocumentLocation"), before.timeline,
    "Original translation/permission/location behavior changed");
  assert.deepEqual(after.warnings, before.warnings);
  assert.equal(after.initialBlank, before.initialBlank);
  const locations = after.messages.filter(message => message.type === "GeckoView:LocationChange");
  const visibleCalls = options.calls.filter((call, index) => !(index === 0 && options.initialBlank && call.uri === "about:blank"));
  assert.equal(locations.length, visibleCalls.length);
  let nextNotification = 0;
  for (let index = 0; index < visibleCalls.length; index++) {
    const call = visibleCalls[index];
    const locationIndex = after.messages.indexOf(locations[index]);
    if (call.top) {
      const notification = notifications[nextNotification++];
      assert.deepEqual(notification, { type: "Miru:DocumentLocation", payload: { sameDocument: !!(call.flags & SAME_DOCUMENT) } });
      assert.strictEqual(after.messages[locationIndex - 1], notification, "Notification must directly precede the original sender");
      assert.equal(typeof notification.payload.sameDocument, "boolean");
    }
  }
  assert.equal(notifications.length, nextNotification, "Subframe must not emit a document notification");
  checks++;
}

for (const top of [true, false]) {
  for (const flags of [0, SAME_DOCUMENT, 2, SAME_DOCUMENT | 2]) {
    for (const privateMode of [false, true]) {
      for (const gesture of [true, false, null]) {
        compare({ calls: [{ top, flags, uri: "https://fixture.invalid/page" }], privateMode, gesture });
      }
    }
  }
}
for (const options of [{ principal: false }, { supported: false }, { exposeThrows: true }, { permissionsThrow: true }]) {
  compare({ ...options, calls: [{ top: true, flags: 0, uri: "https://fixture.invalid/page" }] });
}
compare({ initialBlank: true, calls: [{ top: true, flags: 0, uri: "about:blank" }] });
compare({ calls: [
  { top: true, flags: 0, uri: "https://fixture.invalid/page" },
  { top: true, flags: SAME_DOCUMENT, uri: "https://fixture.invalid/page#fragment" },
  { top: true, flags: SAME_DOCUMENT, uri: "https://fixture.invalid/history-state" },
  { top: true, flags: 0, uri: "https://fixture.invalid/history-state" },
  { top: false, flags: 0, uri: "https://iframe.invalid/new-document" },
] });
console.log(`PASS: ${checks} actual-module document-location scenarios with local stubs; native runtime not tested`);
