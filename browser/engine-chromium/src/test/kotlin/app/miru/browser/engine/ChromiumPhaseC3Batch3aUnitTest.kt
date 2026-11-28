// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromiumPhaseC3Batch3aUnitTest {

    private class MemorySharedPreferences : SharedPreferences {
        private val map = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) map.clear()
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
        }
    }

    private class FakeContext : ContextWrapper(null) {
        private val prefs = mutableMapOf<String, MemorySharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            prefs.getOrPut(name) { MemorySharedPreferences() }
    }

    private fun memoryStore(): ChromiumSitePermissionStore {
        val backing = linkedMapOf<String, String>()
        return ChromiumSitePermissionStore(
            readPersisted = { backing[it] },
            writePersisted = { key, value -> if (value == null) backing.remove(key) else backing[key] = value },
            clearPersisted = { backing.clear() },
            readAllPersisted = { LinkedHashMap(backing) },
        )
    }

    private fun memoryAutoplay(): AutoplayPermissions {
        val backing = linkedMapOf<String, String>()
        return AutoplayPermissions(
            { backing[it] },
            { key, value -> if (value == null) backing.remove(key) else backing[key] = value },
            { backing.clear() },
            { LinkedHashMap(backing) },
        )
    }

    @Test
    fun permissionStoreIsolatesPrivateAndPersistsNormalOrigins() {
        val store = memoryStore()
        store.put("https://a.example/path", isPrivate = false, SitePermissionKind.LOCATION, SitePermissionChoice.ALLOW)
        store.put("https://b.example/", isPrivate = true, SitePermissionKind.CAMERA, SitePermissionChoice.ALLOW)

        assertEquals(SitePermissionChoice.ALLOW, store.readOne("https://a.example", isPrivate = false, SitePermissionKind.LOCATION))
        assertEquals(SitePermissionChoice.ASK, store.readOne("https://a.example", isPrivate = true, SitePermissionKind.LOCATION))
        assertEquals(SitePermissionChoice.ALLOW, store.readOne("https://b.example", isPrivate = true, SitePermissionKind.CAMERA))
        assertEquals(mapOf("https://a.example" to mapOf(SitePermissionKind.LOCATION to SitePermissionChoice.ALLOW)), store.listSavedDefaultOrigins())

        store.clearPrivate()
        assertEquals(SitePermissionChoice.ASK, store.readOne("https://b.example", isPrivate = true, SitePermissionKind.CAMERA))
        assertEquals(SitePermissionChoice.ALLOW, store.readOne("https://a.example", isPrivate = false, SitePermissionKind.LOCATION))

        store.reset("https://a.example", isPrivate = false)
        assertTrue(store.listSavedDefaultOrigins().isEmpty())
    }

    @Test
    fun permissionsControllerHandlesTemporaryDecisionsPersistenceAndAutoplay() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val ctx = FakeContext()
            val store = memoryStore()
            val autoplay = memoryAutoplay()
            var selected = "tab-1"
            var hasPrivate = false
            val tabs = linkedMapOf(
                "tab-1" to (101L to ChromiumPermissionsController.TabMeta("tab-1", "https://site.test/perm", false, 0L, 1L)),
                "tab-2" to (202L to ChromiumPermissionsController.TabMeta("tab-2", "https://priv.test/perm", true, 0L, 1L)),
            )
            val resolvedSite = mutableListOf<Pair<Long, Boolean>>()
            val resolvedMedia = mutableListOf<Pair<Long, Boolean>>()
            val changedSites = mutableListOf<Pair<String, Boolean>>()
            var clearedAll = 0
            val grantedSystem = mutableSetOf(Manifest.permission.ACCESS_FINE_LOCATION)
            var notificationsEnabled = true

            val controller = ChromiumPermissionsController(
                context = ctx,
                scope = scope,
                selectedTabId = { selected },
                lookupTab = { tabs[it]?.second },
                lookupTabByNativePtr = { ptr -> tabs.values.firstOrNull { it.first == ptr }?.second },
                hasPrivateTabs = { hasPrivate },
                resolveSitePermissionsNative = { id, ok -> resolvedSite += id to ok },
                resolveMediaAccessNative = { id, ok -> resolvedMedia += id to ok },
                onSiteChanged = { origin, isPrivate -> changedSites += origin to isPrivate },
                onAllSiteSettingsCleared = { clearedAll++ },
                store = store,
                autoplay = autoplay,
                systemPermissionChecker = { it in grantedSystem },
                notificationsEnabledChecker = { notificationsEnabled },
                mainDispatcher = Dispatchers.Unconfined,
            )

            // 1. Temporary denial shows BLOCK in siteState, denies native request, and clears on navigation.
            controller.onRequestSitePermissions(1L, 101L, "https://site.test/perm", "https://site.test/perm", false, 1 shl 2)
            val p1 = controller.prompt.value!!
            assertEquals(setOf(SitePermissionKind.LOCATION), p1.kinds)
            controller.answer(p1.token, allow = false, remember = false)
            assertEquals(listOf(1L to false), resolvedSite)
            assertEquals(2, controller.querySitePermission("https://site.test", false, 2, 101L))
            controller.refreshSite()
            assertEquals(SitePermissionChoice.BLOCK, controller.siteState.value!!.choices[SitePermissionKind.LOCATION])
            assertTrue(controller.listSavedDefaultPermissions().isEmpty())

            tabs["tab-1"] = 101L to tabs.getValue("tab-1").second.copy(locationGeneration = 2L)
            controller.onNavigation("tab-1")
            assertEquals(0, controller.querySitePermission("https://site.test", false, 2, 101L))
            assertEquals(SitePermissionChoice.ASK, controller.siteState.value!!.choices[SitePermissionKind.LOCATION])

            // 2. Notification & Protected Media always persist even when remember = false.
            controller.onRequestSitePermissions(2L, 101L, "https://site.test/perm", "https://site.test/perm", false, (1 shl 3) or (1 shl 4))
            val p2 = controller.prompt.value!!
            controller.answer(p2.token, allow = true, remember = false)
            assertEquals(true, resolvedSite.last().second)
            assertEquals(1, controller.querySitePermission("https://site.test", false, 3, 101L))
            assertEquals(1, controller.querySitePermission("https://site.test", false, 4, 101L))
            assertTrue(controller.notificationAllowed("https://site.test", isPrivate = false))
            assertEquals(
                mapOf(
                    SitePermissionKind.NOTIFICATIONS to SitePermissionChoice.ALLOW,
                    SitePermissionKind.PROTECTED_MEDIA to SitePermissionChoice.ALLOW,
                ),
                controller.listSavedDefaultPermissions()["https://site.test"],
            )

            // 3. Autoplay query maps 0=BLOCK_AUDIBLE, 1=ALLOW_ALL, 2=BLOCK_ALL and notifies site change.
            assertEquals(0, controller.queryAutoplayPolicy("https://site.test/video", false))
            controller.setAutoplayForSite(controller.siteState.value!!.token, SiteAutoplayChoice.ALLOW_ALL)
            assertEquals(1, controller.queryAutoplayPolicy("https://site.test/video", false))
            controller.setAutoplayForSite(controller.siteState.value!!.token, SiteAutoplayChoice.BLOCK_ALL)
            assertEquals(2, controller.queryAutoplayPolicy("https://site.test/video", false))
            assertEquals(mapOf("https://site.test" to SiteAutoplayChoice.BLOCK_ALL), controller.listSavedDefaultAutoplay())
            assertTrue(changedSites.contains("https://site.test" to false))

            // 4. Android-first media capture keeps site permission as ASK when OS runtime CAMERA permission is denied.
            controller.onRequestMediaAccess(3L, 101L, "https://site.test/perm", "https://site.test/perm", false, 1 shl 0)
            val runtimeReq = controller.runtimeRequest.value!!
            assertEquals(listOf(Manifest.permission.CAMERA), runtimeReq.permissions)
            assertTrue(controller.markRuntimeRequestLaunched(runtimeReq.token, emptySet()))
            val requiresSettingsFirst = controller.onRuntimePermissionsResult(
                mapOf(Manifest.permission.CAMERA to false),
                setOf(Manifest.permission.CAMERA),
            )
            assertEquals(false, requiresSettingsFirst)
            assertEquals(listOf(3L to false), resolvedMedia)
            assertNull(controller.prompt.value)
            assertEquals(SitePermissionChoice.ASK, store.readOne("https://site.test", false, SitePermissionKind.CAMERA))

            // Repeated denial transitioning out of rationale triggers settings recovery without storing site BLOCK.
            controller.onRequestMediaAccess(4L, 101L, "https://site.test/perm", "https://site.test/perm", false, 1 shl 0)
            val runtimeReq2 = controller.runtimeRequest.value!!
            assertTrue(controller.markRuntimeRequestLaunched(runtimeReq2.token, setOf(Manifest.permission.CAMERA)))
            val requiresSettingsSecond = controller.onRuntimePermissionsResult(
                mapOf(Manifest.permission.CAMERA to false),
                emptySet(),
            )
            assertEquals(true, requiresSettingsSecond)
            assertEquals(listOf(3L to false, 4L to false), resolvedMedia)
            assertEquals(SitePermissionChoice.ASK, store.readOne("https://site.test", false, SitePermissionKind.CAMERA))

            // 5. Private tab grants stay isolated and wipe when private tabs close.
            hasPrivate = true
            selected = "tab-2"
            controller.refreshSite()
            controller.setForSite(controller.siteState.value!!.token, SitePermissionKind.NOTIFICATIONS, SitePermissionChoice.ALLOW)
            assertTrue(controller.notificationAllowed("https://priv.test", isPrivate = true))
            hasPrivate = false
            controller.syncState()
            assertFalse(controller.notificationAllowed("https://priv.test", isPrivate = true))

            // 6. Clear all site settings clears stores, autoplay, and notifies listeners.
            runBlocking {
                controller.clearAllSiteSettings { }
            }
            assertEquals(1, clearedAll)
            assertTrue(controller.listSavedDefaultPermissions().isEmpty())
            assertTrue(controller.listSavedDefaultAutoplay().isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
