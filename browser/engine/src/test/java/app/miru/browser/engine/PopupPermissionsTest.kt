// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import org.junit.Assert.*
import org.junit.Test

class PopupPermissionsTest {
    private val saved = mutableMapOf<String, Boolean>()
    private fun policy() = PopupPermissions({ saved[it] == true }, { key, allowed -> if(allowed) saved[key] = true else saved.remove(key) }, saved::clear)

    @Test fun ordinaryChoicesSurviveRecreationButStayInTheExactOriginAndContainer() {
        val policy = policy()
        assertFalse(policy.allowed("https://EXAMPLE.com/path", false, null))
        policy.set("https://EXAMPLE.com:443/path?q=1", false, null, true)
        val restored = policy()
        assertTrue(restored.allowed("https://example.com/other", false, null))
        for(url in listOf("http://example.com", "https://example.com:444", "https://other.example")) assertFalse(restored.allowed(url, false, null))
        assertFalse(restored.allowed("https://example.com", false, "another-container"))
        restored.set("https://example.com", false, null, false)
        assertTrue(saved.isEmpty())
    }
    @Test fun privateChoicesNeverReadOrWriteOrdinaryPreferencesAndCloseClearsThem() {
        val policy = policy()
        policy.set("https://example.com", false, null, true)
        assertFalse(policy.allowed("https://example.com", true, null))
        val before = saved.toMap()
        policy.set("https://example.com", true, null, true)
        assertTrue(policy.allowed("https://example.com", true, null))
        assertEquals(before, saved)
        policy.clearPrivate()
        assertFalse(policy.allowed("https://example.com", true, null))
        assertTrue(policy.allowed("https://example.com", false, null))
        policy.set("https://other.example", true, null, true)
        policy.clearAll()
        assertTrue(saved.isEmpty())
        assertFalse(policy.allowed("https://other.example", true, null))
    }
    @Test fun internalOriginsCannotBeGrantedAndTargetsCannotLaunchFilesOrApps() {
        val policy = policy()
        for(url in listOf("about:newtab", "file:///tmp/a", "https://user:pass@example.com")) {
            assertFalse(policy.set(url, false, null, true))
            assertFalse(policy.allowed(url, false, null))
        }
        for(url in listOf("", "about:blank", "https://example.com/path", "http://localhost:8123")) assertTrue(url, isWebPopupTarget(url))
        for(url in listOf("intent://a", "market://details", "file:///tmp/a", "javascript:alert(1)", "data:text/html,a")) assertFalse(url, isWebPopupTarget(url))
    }
}
