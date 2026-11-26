// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import org.chromium.build.NativeLibraries
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromiumEngineContractTest {
    @Test
    fun nativeLibrariesLoadOrderPlacesMiruCoreBeforeChromium() {
        assertArrayEquals(arrayOf("miru_core", "miru_chromium"), NativeLibraries.LIBRARIES)
        assertTrue(NativeLibraries.sSupport64Bit)
        assertEquals(false, NativeLibraries.sSupport32Bit)
    }

    @Test
    fun auxControllersExposeExpectedDefaults() {
        val permissions = ChromiumPermissionsController()
        val prompts = ChromiumPromptsController()
        val menus = ChromiumContextMenusController()
        val links = ChromiumExternalLinksController()
        assertEquals(null, permissions.prompt.value)
        assertEquals(null, prompts.prompt.value)
        assertEquals(null, menus.state.value)
        assertEquals(null, links.prompt.value)
    }
}
