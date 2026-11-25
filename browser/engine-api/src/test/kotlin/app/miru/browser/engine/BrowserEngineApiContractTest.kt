// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import app.miru.data.BrowsingDataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserEngineApiContractTest {
    @Test fun browserSnapshotSelectsActiveTabAndDetectsNewTab() {
        val ntp = BrowserTab(id = "t1", url = NEW_TAB_URL)
        val web = BrowserTab(id = "t2", url = "https://example.com", title = "Example")
        val snapshot = BrowserSnapshot(tabs = listOf(ntp, web), selectedTabId = "t2", ready = true)
        assertTrue(ntp.isNewTab)
        assertFalse(web.isNewTab)
        assertEquals("t2", snapshot.selectedTab?.id)
        assertNull(BrowserSnapshot(tabs = listOf(ntp), selectedTabId = "missing").selectedTab)
    }

    @Test fun choiceSelectionRejectsGroupsSeparatorsAndDisabledChanges() {
        val choices = listOf(
            BrowserChoice("g1", "Group", enabled = true, selected = false, group = true),
            BrowserChoice("a", "A", enabled = true, selected = false, depth = 1),
            BrowserChoice("b", "B", enabled = false, selected = true, depth = 1),
            BrowserChoice("sep", "", enabled = false, selected = false, separator = true),
        )
        assertFalse(validChoiceSelection(choices, setOf("g1"), multiple = false))
        assertFalse(validChoiceSelection(choices, setOf("sep"), multiple = false))
        assertTrue(validChoiceSelection(choices, setOf("a"), multiple = false))
        assertFalse(validChoiceSelection(choices, setOf("a"), multiple = true))
        assertTrue(validChoiceSelection(choices, setOf("a", "b"), multiple = true))
    }

    @Test fun engineDataTypesAndSniffedMediaRedactSecrets() {
        assertTrue(BrowsingDataType.COOKIES in BrowserEngineDataTypes.ENGINE_TYPES)
        assertTrue(BrowsingDataType.SITE_SETTINGS in BrowserEngineDataTypes.ENGINE_TYPES)
        assertFalse(BrowsingDataType.HISTORY in BrowserEngineDataTypes.ENGINE_TYPES)

        val sniffed = SniffedNetworkMedia(
            tabId = "t1",
            pageUrl = "https://private.example.com/secret?token=123",
            isPrivate = true,
            mediaUrl = "https://cdn.example.com/stream.m3u8?auth=secret",
            contentType = "application/vnd.apple.mpegurl",
            headers = mapOf("Cookie" to "session=supersecret"),
        )
        assertFalse(sniffed.toString().contains("secret"))
        assertFalse(sniffed.toString().contains("private.example.com"))
    }
}
