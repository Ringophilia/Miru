// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import org.junit.Assert.*
import org.junit.Test

class AutoplayPermissionsTest {
    private val saved = mutableMapOf<String, String>()
    private fun policy() = AutoplayPermissions(saved::get, { key, value -> if (value == null) saved.remove(key) else saved[key] = value }, saved::clear)

    @Test fun defaultAllowsMutedButOnlyAllowAllPermitsAudiblePlayback() {
        for (choice in SiteAutoplayChoice.entries) {
            assertEquals(choice == SiteAutoplayChoice.ALLOW_ALL, choice.allows(inaudible = false))
            assertEquals(choice != SiteAutoplayChoice.BLOCK_ALL, choice.allows(inaudible = true))
        }
        assertEquals(SiteAutoplayChoice.BLOCK_AUDIBLE, policy().choice("https://example.com", false, null))
    }

    @Test fun ordinaryChoiceSurvivesRecreationAndStaysInItsExactOriginAndContainer() {
        policy().set("https://EXAMPLE.com:443/path", false, null, SiteAutoplayChoice.ALLOW_ALL)
        val restored = policy()
        assertEquals(SiteAutoplayChoice.ALLOW_ALL, restored.choice("https://example.com/elsewhere", false, null))
        for (url in listOf("http://example.com", "https://example.com:444", "https://other.example")) {
            assertEquals(SiteAutoplayChoice.BLOCK_AUDIBLE, restored.choice(url, false, null))
        }
        assertEquals(SiteAutoplayChoice.BLOCK_AUDIBLE, restored.choice("https://example.com", false, "container"))
        assertEquals(SiteAutoplayChoice.BLOCK_AUDIBLE, restored.choice("https://example.com", false, ""))
        restored.set("https://example.com", false, null, SiteAutoplayChoice.BLOCK_AUDIBLE)
        assertTrue(saved.isEmpty())
    }

    @Test fun privateChoicesNeverTouchDiskAndRetireAtTheLastPrivateTabOrClearAll() {
        val policy = policy()
        policy.set("https://example.com", false, null, SiteAutoplayChoice.ALLOW_ALL)
        val before = saved.toMap()
        assertEquals(SiteAutoplayChoice.BLOCK_AUDIBLE, policy.choice("https://example.com", true, null))
        policy.set("https://example.com", true, null, SiteAutoplayChoice.BLOCK_ALL)
        assertEquals(SiteAutoplayChoice.BLOCK_ALL, policy.choice("https://example.com", true, null))
        assertEquals(before, saved)
        policy.clearPrivate()
        assertEquals(SiteAutoplayChoice.BLOCK_AUDIBLE, policy.choice("https://example.com", true, null))
        assertEquals(SiteAutoplayChoice.ALLOW_ALL, policy.choice("https://example.com", false, null))
        policy.clearAll()
        assertTrue(saved.isEmpty())
        for (url in listOf("about:newtab", "file:///tmp/media", "https://user:password@example.com")) {
            policy.set(url, false, null, SiteAutoplayChoice.ALLOW_ALL)
        }
        assertTrue(saved.isEmpty())
    }
}
