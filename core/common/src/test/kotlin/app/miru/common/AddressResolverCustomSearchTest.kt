// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressResolverCustomSearchTest {
    @Test fun validTemplatesAcceptHttpsAndHttpWithPlaceholder() {
        assertTrue(validateCustomSearchTemplate("https://kagi.com/search?q=%s"))
        assertTrue(validateCustomSearchTemplate("  http://localhost:8888/search?q=%s  "))
    }

    @Test fun invalidTemplatesAreRejected() {
        listOf(
            "",
            "https://kagi.com/search?q=noplaceholder",
            "ftp://example.com/?q=%s",
            "javascript:alert(%s)",
            "https://user:pass@example.com/?q=%s",
            "https://example.com/search?q=%s extra",
        ).forEach { template ->
            assertFalse(template, validateCustomSearchTemplate(template))
        }
    }

    @Test fun customEngineUsesValidTemplateAndFallsBackToGoogleWhenMissingOrInvalid() {
        assertEquals(
            AddressTarget.Web("https://kagi.com/search?q=a+%26+b"),
            AddressResolver.resolve("a & b", SearchEngine.CUSTOM, "https://kagi.com/search?q=%s"),
        )
        assertEquals(
            AddressTarget.Web("https://www.google.com/search?q=a+%26+b"),
            AddressResolver.resolve("a & b", SearchEngine.CUSTOM, "not a template"),
        )
    }
}
