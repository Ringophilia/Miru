// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UblockPageWarningsTest {
    private val url = "https://example.com/page"

    @Test fun `a first load missed by store collection retains its warning after ready`() {
        val pages = UblockPageWarnings()
        pages.loadRequested("tab", url, unprotected = true)
        // The complete load can happen before the collector ever observes loading=true.
        pages.loadingChanged("tab", false, url, unprotected = false)
        assertTrue(pages.needsReload("tab"))
    }

    @Test fun `late request and loading callbacks do not reclassify an early navigation as protected`() {
        val pages = UblockPageWarnings()
        pages.loadRequested("tab", url, unprotected = true)
        pages.loadRequest("tab", url, redirect = false, unprotected = false)
        pages.loadingChanged("tab", true, url, unprotected = false)
        pages.loadingChanged("tab", true, url, unprotected = false)
        pages.locationChanged("tab", url)
        pages.loadingChanged("tab", false, url, unprotected = false)
        assertTrue(pages.needsReload("tab"))
    }

    @Test fun `the old page's late stop cannot discard a newer early navigation`() {
        val pages = UblockPageWarnings()
        pages.loadRequest("tab", "https://example.org/old", redirect = false, unprotected = false)
        pages.loadingChanged("tab", true, "https://example.org/old", unprotected = false)
        pages.loadRequested("tab", url, unprotected = true)
        pages.loadingChanged("tab", false, "https://example.org/old", unprotected = false)
        pages.loadRequest("tab", url, redirect = false, unprotected = false)
        pages.loadingChanged("tab", true, url, unprotected = false)
        pages.locationChanged("tab", url)
        assertTrue(pages.needsReload("tab"))
    }

    @Test fun `old loading start and stop cannot consume a newer early command`() {
        val pages = UblockPageWarnings()
        val previous = "https://example.org/old"
        pages.loadRequest("tab", previous, redirect = false, unprotected = false)
        pages.loadRequested("tab", url, unprotected = true)
        pages.loadingChanged("tab", true, previous, unprotected = false)
        pages.loadingChanged("tab", false, previous, unprotected = false)
        pages.loadRequest("tab", url, redirect = false, unprotected = false)
        pages.loadingChanged("tab", true, url, unprotected = false)
        pages.locationChanged("tab", url)
        assertTrue(pages.needsReload("tab"))
    }

    @Test fun `old document location cannot commit a different failed destination`() {
        val pages = earlyPage()
        val destination = "https://example.org/new"
        pages.loadRequested("tab", destination, unprotected = false)
        pages.locationChanged("tab", "$url#old-document")
        pages.loadRequest("tab", destination, redirect = false, unprotected = false)
        pages.loadingChanged("tab", true, destination, unprotected = false)
        pages.loadingChanged("tab", false, destination, unprotected = false)
        assertTrue(pages.needsReload("tab"))
    }

    @Test fun `unknown loading fallback cannot reinterpret the old warning after ready`() {
        val pages = earlyPage()
        pages.loadingChanged("tab", true, url, unprotected = false)
        pages.locationChanged("tab", url)
        pages.loadingChanged("tab", false, url, unprotected = false)
        assertTrue(pages.needsReload("tab"))
    }

    @Test fun `a successful same URL reload after ready clears the old warning at commit`() {
        val pages = earlyPage()
        pages.loadRequested("tab", url, unprotected = false)
        assertTrue("A command alone does not replace the old document", pages.needsReload("tab"))
        pages.loadRequest("tab", url, redirect = false, unprotected = false)
        pages.loadingChanged("tab", true, url, unprotected = false)
        assertTrue(pages.needsReload("tab"))
        pages.locationChanged("tab", url)
        assertFalse(pages.needsReload("tab"))
    }

    @Test fun `a later failed navigation cannot hide the old document's warning`() {
        val pages = earlyPage()
        val failedUrl = "https://unreachable.example/page"
        pages.loadRequested("tab", failedUrl, unprotected = false)
        pages.loadRequest("tab", failedUrl, redirect = false, unprotected = false)
        pages.loadingChanged("tab", true, url, unprotected = false)
        // Gecko has stopped without committing a different document/location.
        pages.loadingChanged("tab", false, url, unprotected = false)
        assertTrue(pages.needsReload("tab"))
    }

    @Test fun `redirects retain the protection state at the beginning of the navigation`() {
        val pages = UblockPageWarnings()
        pages.loadRequest("tab", url, redirect = false, unprotected = true)
        pages.loadingChanged("tab", true, url, unprotected = false)
        val destination = "https://example.org/destination"
        pages.loadRequest("tab", destination, redirect = true, unprotected = false)
        pages.loadingChanged("tab", true, destination, unprotected = false)
        pages.locationChanged("tab", destination)
        assertTrue(pages.needsReload("tab"))
    }

    @Test fun `web navigation uses its request URL even if the store still contains NTP`() {
        val pages = UblockPageWarnings()
        pages.loadingChanged("tab", true, "about:newtab", unprotected = true)
        // Native readiness changed after the first load event, before the location/store update.
        pages.loadRequest("tab", url, redirect = false, unprotected = false)
        pages.locationChanged("tab", url)
        assertTrue(pages.needsReload("tab"))
    }

    @Test fun `location before loading also waits for an actual load before clearing the warning`() {
        val pages = earlyPage()
        pages.loadRequested("tab", url, unprotected = false)
        pages.loadRequest("tab", url, redirect = false, unprotected = false)
        pages.locationChanged("tab", url)
        assertTrue(pages.needsReload("tab"))
        pages.loadingChanged("tab", true, url, unprotected = false)
        assertFalse(pages.needsReload("tab"))
    }

    @Test fun `a fragment location change without a new load preserves the warning`() {
        val pages = earlyPage()
        pages.locationChanged("tab", "$url#section")
        assertTrue(pages.needsReload("tab"))
    }

    @Test fun `disabled extension and internal pages do not create a reload warning`() {
        val pages = UblockPageWarnings()
        pages.loadRequested("disabled", url, unprotected = false)
        pages.loadRequested("internal", "about:newtab", unprotected = true)
        assertFalse(pages.needsReload("disabled"))
        assertFalse(pages.needsReload("internal"))
        assertFalse(pages.needsReload(null))
    }

    @Test fun `warnings belong to individual tabs and survive recoverable close`() {
        val pages = earlyPage()
        pages.loadRequested("other", url, unprotected = false)
        pages.retainTabs(setOf("tab", "other")) // includes undoHistory's tab id
        assertTrue(pages.needsReload("tab"))
        assertFalse(pages.needsReload("other"))
        pages.retainTabs(setOf("other")) // undo history expired
        pages.loadRequested("tab", url, unprotected = false)
        assertFalse("A new tab cannot inherit a removed page's warning", pages.needsReload("tab"))
    }

    private fun earlyPage(): UblockPageWarnings = UblockPageWarnings().also {
        it.loadRequested("tab", url, unprotected = true)
        it.loadRequest("tab", url, redirect = false, unprotected = true)
        it.loadingChanged("tab", true, url, unprotected = true)
        it.locationChanged("tab", url)
        it.loadingChanged("tab", false, url, unprotected = true)
    }
}
