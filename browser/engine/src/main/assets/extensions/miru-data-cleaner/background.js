// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
"use strict";

// This background capability is not exposed to content scripts or page messages.
function connect() {
  const port = browser.runtime.connectNative("miru_data_cleaner");
  port.onMessage.addListener(async message => {
    if (message?.kind !== "clear-cookies" || typeof message.id !== "string" ||
        message.id.length !== 36 || !Number.isSafeInteger(message.since) || message.since < 0) return;
    let ok = false;
    try {
      // Gecko 158 removes cookies by their creationTime. The unfiltered all-time
      // path calls nsICookieManager.removeAll(), which leaves the private jar.
      // cookieStoreId forces its explicit cookie enumeration path, including
      // getCookiesWithOriginAttributes({ privateBrowsingId: 1 }). Keep the same
      // cutoff for both jars; neither raw cookies nor site identifiers cross IPC.
      const options = message.since ? { since: message.since } : {};
      await browser.browsingData.removeCookies(options);
      await browser.browsingData.removeCookies({ ...options, cookieStoreId: "firefox-private" });
      ok = true;
    } catch (_) { }
    try { port.postMessage({ kind: "clear-result", id: message.id, ok }); } catch (_) { }
  });
  port.onDisconnect.addListener(() => setTimeout(connect, 500));
}
connect();
