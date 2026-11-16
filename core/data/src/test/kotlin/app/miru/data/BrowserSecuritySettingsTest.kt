// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserSecuritySettingsTest {
    @Test fun acceptsHttpsProviderEndpoints() {
        assertEquals("https://resolver.example/dns-query", validatedSecureDnsUrl(" https://resolver.example/dns-query "))
        assertEquals("https://[::1]:8443/resolve?fixed=value", validatedSecureDnsUrl("https://[::1]:8443/resolve?fixed=value"))
    }

    @Test fun rejectsIncompleteOrUnsafeProviderEndpoints() {
        listOf(
            "", "resolver.example/dns-query", "http://resolver.example/dns-query", "https:///dns-query",
            "https://name:password@resolver.example/dns-query", "https://resolver.example/dns-query#fragment",
            "https://resolver.example:65536/", "https://resolver.example:0/", "https://resolver.example/dns query",
        ).forEach { assertNull(it, validatedSecureDnsUrl(it)) }
    }
}
