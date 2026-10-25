// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
// Executes the actual pinned JS with local stubs. No Gecko, Android, or network is used.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import vm from "node:vm";

const sourceDirectory = resolve(process.argv[2] ?? "third_party/mozilla-permission-policy");
const original = await readFile(resolve(sourceDirectory, "upstream/GeckoViewPermission.sys.mjs"), "utf8");
const patched = await readFile(resolve(sourceDirectory, "GeckoViewPermission.sys.mjs"), "utf8");
const importLine = 'import { GeckoViewUtils } from "resource://gre/modules/GeckoViewUtils.sys.mjs";';
const exportedClass = "export class GeckoViewPermission";

async function exercise(source, type, decision, appGranted = true) {
  const writes = [], appRequests = [], contentRequests = [], outcomes = [];
  assert.equal(source.split(importLine).length, 2);
  assert.equal(source.split(exportedClass).length, 2);
  const principal = { URI: { displaySpec: "https://permission.invalid/" }, origin: "https://permission.invalid", originAttributes: {}, privateBrowsingId: 0 };
  const actor = {
    async getContentPermission(data) { contentRequests.push(data); return decision; },
    async getAppPermissions(permissions) { appRequests.push(permissions); return appGranted; },
  };
  const context = {
    __utils: { initLogging: () => ({ debug() {}, warn() {} }) },
    ChromeUtils: {
      defineESModuleGetters(target) {
        target.E10SUtils = { serializePrincipal: () => "serialized-principal" };
        target.EventDispatcher = { instance: { registerListener() {} } };
      },
      generateQI: () => () => {},
    },
    Components: { ID: value => value },
    Ci: { nsIArray: {}, nsIContentPermissionType: {} },
    console: { error: (...args) => { throw new Error(`Unexpected module error: ${args}`); } },
    Services: {
      uuid: { generateUUID: () => "{synthetic-request}" },
      prefs: { getBoolPref: () => false },
      perms: { ALLOW_ACTION: 1, DENY_ACTION: 2, EXPIRE_NEVER: 0, addFromPrincipal: (...args) => writes.push(args) },
    },
  };
  vm.runInNewContext(source.replace(importLine, "const GeckoViewUtils = globalThis.__utils;")
    .replace(exportedClass, "class GeckoViewPermission") + "\nglobalThis.PermissionClass = GeckoViewPermission;", context);
  const permission = { type, capability: 3 };
  const request = {
    types: { QueryInterface: () => ({ length: 1, queryElementAt: () => permission }) },
    principal, topLevelPrincipal: principal,
    window: { browsingContext: { currentWindowGlobal: { getActor: () => actor } } },
    cancel: () => outcomes.push("cancel"), allow: value => outcomes.push(["allow", value]),
  };
  await new context.PermissionClass().prompt(request);
  // Normalize objects allocated inside the VM before comparing across runs.
  return JSON.parse(JSON.stringify({ writes, appRequests, contentRequests, outcomes }));
}

let checks = 0;
for (const decision of [1, 2, 3]) {
  for (const type of ["geolocation", "desktop-notification", "persistent-storage", "media-key-system-access", "local-device-access", "local-network-access", "storage-access"]) {
    const before = await exercise(original, type, decision), after = await exercise(patched, type, decision);
    assert.deepEqual(after.outcomes, before.outcomes, `${type}/${decision}: final request outcome changes`);
    assert.deepEqual(after.appRequests, before.appRequests, `${type}/${decision}: native permission request changes`);
    assert.deepEqual(after.contentRequests, before.contentRequests, `${type}/${decision}: content permission request changes`);
    if (type === "geolocation") assert.deepEqual(after.writes, [], `geolocation/${decision}: native decision persisted`);
    else assert.deepEqual(after.writes, before.writes, `${type}/${decision}: unrelated native permission persistence changes`);
    checks++;
  }
}
const beforeDenied = await exercise(original, "geolocation", 1, false), afterDenied = await exercise(patched, "geolocation", 1, false);
assert.deepEqual(afterDenied.outcomes, beforeDenied.outcomes);
assert.deepEqual(afterDenied.outcomes, ["cancel"]);
assert.deepEqual(afterDenied.writes, []);
checks++;
console.log(`PASS: ${checks} actual-module permission policy scenarios with local stubs; native runtime not tested`);
