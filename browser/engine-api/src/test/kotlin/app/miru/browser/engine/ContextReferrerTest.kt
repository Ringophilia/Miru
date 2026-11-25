// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextReferrerTest {
    @Test fun `same origin retains the document query but removes credentials and fragment`() {
        assertEquals("https://example.test/private/view?token=secret",
            contextReferrer("https://name:password@example.test/private/view?token=secret#secret-fragment", "https://example.test/image.png"))
    }

    @Test fun `different origins receive only the document origin`() {
        val page = "https://name:password@example.test/private/view?token=secret#secret-fragment"
        for (target in listOf("https://images.test/image.png", "https://example.test:8443/image.png", "https://sub.example.test/image.png")) {
            assertEquals("https://example.test/", contextReferrer(page, target))
        }
    }

    @Test fun `https downgrade and nonweb destinations receive no referrer`() {
        for (target in listOf("http://example.test/image.png", "http://images.test/image.png", "blob:https://example.test/id", "data:image/png;base64,AA==")) {
            assertNull(contextReferrer("https://example.test/private?token=secret", target))
        }
    }

    @Test fun `default ports and host case are normalized without decoding escaped document characters`() {
        assertEquals("https://example.test/p%2Fq?x=a%23b",
            contextReferrer("HTTPS://EXAMPLE.TEST:443/p%2Fq?x=a%23b#never", "https://example.test/image"))
        assertEquals("http://example.test:8080/", contextReferrer("http://example.test:8080/private", "http://example.test/image"))
        assertEquals("https://[::1]:8443/path", contextReferrer("https://[::1]:8443/path#fragment", "https://[::1]:8443/image"))
    }

    @Test fun `a captured origin or empty policy stays restricted on a same origin request`() {
        val page = "https://example.test/private?token=secret"
        assertEquals("https://example.test/", contextReferrer(page, "https://example.test/image", "https://example.test/"))
        assertNull(contextReferrer(page, "https://example.test/image", ""))
    }

    @Test fun `captured full document is capped to an origin for cross origin actions`() {
        val page = "https://example.test/private?token=secret"
        assertEquals("https://example.test/", contextReferrer(page, "https://images.test/image", "$page#never"))
    }

    @Test fun `redirects further restrict the referrer and cannot regain a private document query`() {
        val page = "https://example.test/private?token=secret#never"
        val initial = contextReferrer(page, "https://example.test/image")
        assertEquals("https://example.test/private?token=secret", initial)
        val external = contextReferrer(page, "https://images.test/image", initial.orEmpty())
        assertEquals("https://example.test/", external)
        assertEquals("https://example.test/", contextReferrer(page, "https://example.test/final", external.orEmpty()))
        assertNull(contextReferrer(page, "http://images.test/image", external.orEmpty()))
    }

    @Test fun `a missing referrer stays absent across redirects`() {
        val page = "https://example.test/private?token=secret"
        val absent: String? = null
        assertNull(contextReferrer(page, "https://example.test/image", absent.orEmpty()))
        assertNull(contextReferrer(page, "https://images.test/image", absent.orEmpty()))
    }

    @Test fun `a captured address cannot substitute a different document or query`() {
        val page = "https://example.test/private?token=secret"
        for (captured in listOf("https://evil.test/", "https://example.test/other", "https://example.test/private?token=other", "javascript:alert(1)")) {
            assertNull(contextReferrer(page, "https://example.test/image", captured))
        }
    }

    @Test fun `invalid sources controls and excessive referrers fail closed`() {
        for (page in listOf("about:blank", "file:///private", "https:///missing", "https://example.test/a\r\nX-Leak:1", "https://example.test:99999/path")) {
            assertNull(contextReferrer(page, "https://example.test/image"))
        }
        assertEquals("https://example.test/", contextReferrer("https://example.test/" + "a".repeat(4_096), "https://example.test/image"))
    }
}
