// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import org.junit.Assert.*
import org.junit.Test

class ContextImagePolicyTest {
    private val page = "https://user:password@example.test/private?token=secret#fragment"
    private val full = "https://example.test/private?token=secret"
    private val origin = "https://example.test/"
    private fun policy(name: String) = requireNotNull(ContextImagePolicy.fromDocument(page, name))

    @Test fun `all eight policies distinguish same origin cross origin and downgrade`() {
        val expected = mapOf(
            "no-referrer" to listOf(null, null, null),
            "no-referrer-when-downgrade" to listOf(full, full, null),
            "same-origin" to listOf(full, null, null),
            "origin" to listOf(origin, origin, origin),
            "strict-origin" to listOf(origin, origin, null),
            "origin-when-cross-origin" to listOf(full, origin, origin),
            "strict-origin-when-cross-origin" to listOf(full, origin, null),
            "unsafe-url" to listOf(full, full, full),
        )
        val targets = listOf("https://example.test/image.png", "https://images.test/image.png", "http://images.test/image.png")
        expected.forEach { (name, values) -> targets.forEachIndexed { index, target -> assertEquals(name, values[index], policy(name).referrerFor(target)) } }
    }

    @Test fun `a verified empty document policy selects the current strict default`() {
        assertEquals(full, policy("").referrerFor("https://example.test/image"))
        assertEquals(origin, policy("").referrerFor("https://images.test/image"))
        assertNull(policy("").referrerFor("http://images.test/image"))
        assertNull(ContextImagePolicy.fromDocument(page, "an-unknown-policy"))
    }

    @Test fun `explicit image policy can override a stricter document policy`() {
        assertNull(policy("no-referrer").referrerFor("https://images.test/image"))
        assertEquals(full, policy("unsafe-url").referrerFor("https://images.test/image"))
    }

    @Test fun `actual frame address supplies the referrer without substituting the parent`() {
        val frame = requireNotNull(ContextImagePolicy.fromDocument("https://frame.test/embed?private=1#fragment", "same-origin"))
        assertEquals("https://frame.test/embed?private=1", frame.referrerFor("https://frame.test/image"))
        assertNull(frame.referrerFor("https://parent.test/image"))
        assertFalse(frame.toString().contains("frame.test"))
    }

    @Test fun `ordinary insecure origins send their permitted referrers without false downgrade`() {
        val insecure = requireNotNull(ContextImagePolicy.fromDocument("http://example.test/document", "no-referrer-when-downgrade"))
        assertEquals("http://example.test/document", insecure.referrerFor("http://images.test/image"))
        assertEquals("http://example.test/document", insecure.referrerFor("https://images.test/image"))
    }

    @Test fun `localhost loopback and https are potentially trustworthy`() {
        assertEquals(origin, policy("strict-origin").referrerFor("http://127.0.0.1/image"))
        assertEquals(origin, policy("strict-origin").referrerFor("http://sub.localhost/image"))
        assertEquals(origin, policy("strict-origin").referrerFor("http://[::1]/image"))
        val loopback = requireNotNull(ContextImagePolicy.fromDocument("http://127.1.2.3/page", "strict-origin"))
        assertNull(loopback.referrerFor("http://images.test/image"))
    }

    @Test fun `normalization preserves escaped bytes and strips default ports credentials and fragment`() {
        val value = requireNotNull(ContextImagePolicy.fromDocument("HTTPS://u:p@EXAMPLE.TEST:443/p%2Fq?x=a%23b#never", "unsafe-url"))
        assertEquals("https://example.test/p%2Fq?x=a%23b", value.referrerFor("https://images.test/image"))
        val ipv6 = requireNotNull(ContextImagePolicy.fromDocument("https://[::1]:8443/path", "same-origin"))
        assertEquals("https://[::1]:8443/path", ipv6.referrerFor("https://[::1]:8443/image"))
    }

    @Test fun `nonweb invalid and oversized sources do not become unsafe headers`() {
        assertNull(ContextImagePolicy.fromDocument("https://example.test/a\r\nInjected:yes", "unsafe-url"))
        assertNull(requireNotNull(ContextImagePolicy.fromDocument("about:srcdoc", "unsafe-url")).referrerFor("https://images.test/image"))
        assertNull(policy("unsafe-url").referrerFor("file:///private"))
        assertNull(policy("unsafe-url").referrerFor("https://images.test:99999/image"))
        val long = requireNotNull(ContextImagePolicy.fromDocument("https://example.test/" + "x".repeat(4_096), "unsafe-url"))
        assertEquals(origin, long.referrerFor("https://images.test/image"))
    }

    @Test fun `redirect response headers select the last recognized standard token`() {
        assertEquals("no-referrer", ContextImagePolicy.responsePolicy("unsupported, unsafe-url, NO-REFERRER"))
        assertEquals("origin", ContextImagePolicy.responsePolicy("origin, unknown"))
        assertNull(ContextImagePolicy.responsePolicy("unknown, "))
    }
}
