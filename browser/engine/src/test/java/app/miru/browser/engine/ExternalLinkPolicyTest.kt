// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import org.junit.Assert.*
import org.junit.Test

class ExternalLinkPolicyTest {
    @Test fun schemesAreCaseInsensitiveAndMalformedOrOversizedInputsAreRejected() {
        assertEquals("weixin", linkScheme("WEIXIN://open"))
        assertEquals("intent", linkScheme("intent://open#Intent;scheme=app;end"))
        for(value in listOf("word", " https://example.com", "weixin://x\n", "a:" + "x".repeat(8192))) assertNull(linkScheme(value))
    }
    @Test fun browserAndLocalResourcesCannotBeDispatchedToOtherApps() {
        for(value in listOf("file:///tmp/a", "content://private/data", "javascript:alert(1)", "data:text/plain,a", "intent://x", "android-app://pkg/x", "about:blank", "miru://settings")) {
            assertFalse(value, externalDataAllowed(value, true))
        }
        for(value in listOf("weixin://open", "market://details?id=example", "mailto:someone@example.com", "tel:123")) assertTrue(value, externalDataAllowed(value, false))
        assertFalse(externalDataAllowed("https://example.com", false))
        assertTrue(externalDataAllowed("https://example.com", true))
    }
    @Test fun fallbackIsOnlyAnOrdinaryWebAddressWithoutCredentialsOrControlCharacters() {
        assertEquals("https://example.com/path?q=1", webFallback("https://example.com/path?q=1"))
        for(value in listOf(null, "javascript:alert(1)", "file:///a", "content://a", "intent://a", "https://user:pass@example.com", "https:///bad", "https://example.com\r\n")) assertNull(webFallback(value))
    }
}
