// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.capability

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.browser.MainActivity
import app.miru.browser.MiruApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Requires POST_NOTIFICATIONS revoked and user-set/user-fixed flags cleared before instrumentation.
 * Permission revocation kills the app, so preparation belongs to the host, never the test process.
 */
@RunWith(AndroidJUnit4::class)
class PrivateNotificationPermissionIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as MiruApplication).container
    private val browser get() = container.browser
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val owners = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
    private fun notification() = manager.activeNotifications.singleOrNull { it.tag == "private_tabs" }?.notification

    @Test fun actualDenialKeepsPrivateBrowsingUsableDoesNotRepeatAndLaterGrantRestoresNotification() {
        assertEquals("Revoke permission before starting this class", PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        compose.waitUntil(30000) { browser.state.value.ready }
        assertTrue("Controlled device must have no pre-existing private tabs", browser.state.value.tabs.none { it.isPrivate })
        val normalIds = browser.state.value.tabs.map { it.id }.toSet()
        val oldPrompted = runBlocking { container.settings.settings.first() }.privateNotificationPrompted
        runBlocking { container.settings.setPrivateNotificationPrompted(false) }
        try {
            compose.runOnIdle { browser.newTab(isPrivate = true) }
            var clicked = false
            compose.waitUntil(10000) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root != null && root.packageName?.toString() in owners) {
                    for (namespace in owners) {
                        root.findAccessibilityNodeInfosByViewId("$namespace:id/permission_deny_button").firstOrNull()?.let {
                            clicked = it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        if (clicked) break
                    }
                }
                clicked
            }
            compose.waitUntil(5000) { browser.state.value.selectedTab?.isPrivate == true && runBlocking { container.settings.settings.first() }.privateNotificationPrompted }
            assertFalse(manager.areNotificationsEnabled()); assertNull(notification())
            compose.activityRule.scenario.recreate()
            compose.runOnIdle { browser.newTab(isPrivate = true) }
            // Give a mistaken second launch enough time to expose the system dialog.
            SystemClock.sleep(750)
            assertTrue(browser.state.value.selectedTab?.isPrivate == true)
            assertFalse(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() in owners)
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            compose.runOnIdle { container.privateTabsNotifications.refresh() }
            compose.waitUntil(5000) { notification() != null }
            notification()!!.contentIntent.send()
            compose.waitUntil(5000) { browser.state.value.tabs.none { it.isPrivate } && notification() == null }
            assertEquals(normalIds, browser.state.value.tabs.map { it.id }.toSet())
        } finally {
            compose.runOnIdle { browser.closePrivateTabsPermanently() }
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            runBlocking { container.settings.setPrivateNotificationPrompted(oldPrompted) }
        }
    }
}
