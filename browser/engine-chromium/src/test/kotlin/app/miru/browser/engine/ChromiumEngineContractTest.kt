// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val ctx = object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
            }
            val permissions = ChromiumPermissionsController(
                context = ctx,
                scope = scope,
                selectedTabId = { null },
                lookupTab = { null },
                lookupTabByNativePtr = { null },
                hasPrivateTabs = { false },
                resolveSitePermissionsNative = { _, _ -> },
                resolveMediaAccessNative = { _, _ -> },
                onSiteChanged = { _, _ -> },
                onAllSiteSettingsCleared = {},
                store = ChromiumSitePermissionStore({ null }, { _, _ -> }, {}, { emptyMap() }),
                autoplay = AutoplayPermissions({ null }, { _, _ -> }, {}, { emptyMap() }),
                systemPermissionChecker = { false },
                notificationsEnabledChecker = { false },
                mainDispatcher = Dispatchers.Unconfined,
            )
            val prompts = ChromiumPromptsController(
                scope = scope,
                selectedTabId = { null },
                lookupTab = { null },
                hasPrivateTabs = { false },
                popupPermissions = PopupPermissions({ false }, { _, _ -> }, {}, { emptySet() }),
                resolveJsDialogNative = { _, _, _, _ -> },
                resolveHttpAuthNative = { _, _, _, _ -> },
                resolveRepostNative = { _, _ -> },
                openPopupTarget = { _, _, _ -> },
            )
            val menus = ChromiumContextMenusController(
                lookupTab = { null },
                selectedTabId = { null },
                evaluateScript = { _, _, _ -> false },
                lookupCapturedHeaders = { _, _, _ -> null },
                fallbackHeaders = { _, _ -> emptyMap() },
                notifyNativeClosed = {},
            )
            val links = ChromiumExternalLinksController(
                lookupTab = { null },
                selectedTabId = { null },
                loadUrlInTab = { _, _ -> },
                resolvePlan = { null },
            )
            assertEquals(null, permissions.prompt.value)
            assertEquals(null, prompts.prompt.value)
            assertEquals(null, menus.state.value)
            assertEquals(null, links.prompt.value)
        } finally {
            scope.cancel()
        }
    }
}
