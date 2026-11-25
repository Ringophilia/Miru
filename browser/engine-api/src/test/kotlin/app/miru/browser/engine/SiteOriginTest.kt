// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteOriginTest {
    @Test fun `web origins discard document components and normalize case`() {
        assertEquals("https://example.test", siteOrigin("HTTPS://EXAMPLE.TEST/a/b?token=secret#fragment"))
        assertEquals("http://example.test", siteOrigin("http://example.test/"))
    }

    @Test fun `default ports normalize while different schemes and ports stay separate`() {
        assertEquals("https://example.test", siteOrigin("https://example.test:443/path"))
        assertEquals("http://example.test", siteOrigin("http://example.test:80/path"))
        assertEquals("https://example.test:80", siteOrigin("https://example.test:80/path"))
        assertEquals("http://example.test:443", siteOrigin("http://example.test:443/path"))
        assertEquals("https://example.test:8443", siteOrigin("https://example.test:8443/path"))
        assertNotEquals(siteOrigin("https://example.test"), siteOrigin("http://example.test"))
        assertNotEquals(siteOrigin("https://example.test"), siteOrigin("https://example.test:8443"))
        assertNotEquals(siteOrigin("https://example.test"), siteOrigin("https://sub.example.test"))
    }

    @Test fun `ipv6 origins retain exactly one bracket pair and the nondefault port`() {
        assertEquals("https://[::1]", siteOrigin("https://[::1]:443/path"))
        assertEquals("https://[2001:db8::1]:8443", siteOrigin("HTTPS://[2001:DB8::1]:8443/path"))
        assertEquals("http://[::1]", siteOrigin("http://[::1]:80"))
    }

    @Test fun `missing opaque nonweb credentialed and malformed inputs are rejected`() {
        for (value in listOf(null, "", " ", "example.test", "//example.test/path", "about:blank", "file:///private",
            "blob:https://example.test/id", "data:text/plain,private", "https:///missing", "https://",
            "https://name:password@example.test/path", "https://@example.test/path", "https://example.test:",
            "https://example.test:-1/path", "https://example.test:65536/path", "https://[::1/path",
            "https://example.test/a\r\nHeader:secret", "https://example.test/a\tpath", "https://example.test/a\u007f")) {
            assertNull(siteOrigin(value))
        }
    }
}
