// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import org.junit.Assert.*
import org.junit.Test

class FaviconUrlTest {
    @Test fun keepsOnlyTheOriginAndSupportsPortsAndIpv6() {
        assertEquals("https://example.com/favicon.ico", faviconUrl("https://example.com/path?q=secret#anchor"))
        assertEquals("http://localhost:8123/favicon.ico", faviconUrl("http://localhost:8123/page"))
        assertEquals("http://[::1]:80/favicon.ico", faviconUrl("http://[::1]:80/page"))
    }
    @Test fun rejectsNonWebPagesAndCredentials() {
        for (url in listOf("about:newtab", "miru://history", "file:///sdcard/a", "data:image/png,a", "search words", "https://user:pass@example.com", "https:///bad")) {
            assertNull(url, faviconUrl(url))
        }
    }
}
