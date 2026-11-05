// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.capability

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.browser.BrowserTabFixture
import app.miru.browser.MainActivity
import app.miru.browser.MiruApplication
import app.miru.browser.NotificationOrigin
import app.miru.browser.R
import app.miru.browser.engine.SitePermissionChoice
import app.miru.browser.engine.SitePermissionKind
import app.miru.data.BrowsingDataClearRequest
import app.miru.data.BrowsingDataRange
import app.miru.data.BrowsingDataType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Host must revoke POST_NOTIFICATIONS and clear user-set/user-fixed flags before instrumentation. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class SiteNotificationPermissionIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity
    private val browser get() = (context.application as MiruApplication).container.browser
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val owners = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
    private fun posted() = manager.activeNotifications.filter { it.tag?.startsWith("site_notification:") == true }
    private fun title(id: String, text: String) = compose.waitUntil(15000) { browser.state.value.tabs.firstOrNull { it.id == id }?.let { it.title == text && !it.loading } == true }
    private fun state(id: String) {
        compose.runOnIdle { browser.permissions.refreshSite() }
        compose.waitUntil(10000) { browser.permissions.siteState.value?.let { it.tabId == id && !it.busy && !it.error } == true }
    }

    @Test fun androidDenialKeepsSiteConsentAndLaterSystemGrantRestoresActualNotifications() {
        assertEquals("Revoke on host before this class", PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        compose.waitUntil(30000) { browser.state.value.ready }
        val tabs = BrowserTabFixture(); compose.runOnIdle { tabs.capture(browser) }
        val oldAccessibilityFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        NotificationOrigin().use { origin ->
            try {
                var id = ""
                compose.runOnIdle { id = browser.newTab(origin.url) }
                title(id, "Notify ready default")
                tapRequest()
                compose.waitUntil(5000) { browser.permissions.prompt.value != null }
                compose.onNodeWithText(context.getString(R.string.site_permission_allow)).performClick()
                var denied = false
                compose.waitUntil(10000) {
                    val root = automation.rootInActiveWindow
                    if (root?.packageName?.toString() in owners) for (owner in owners) {
                        root?.findAccessibilityNodeInfosByViewId("$owner:id/permission_deny_button")?.firstOrNull()?.let {
                            denied = it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        if (denied) break
                    }
                    denied
                }
                title(id, "Notify denied"); state(id)
                assertTrue(posted().isEmpty())
                assertFalse(manager.areNotificationsEnabled())
                assertEquals("Refusing Android does not undo the website choice", SitePermissionChoice.ALLOW,
                    browser.permissions.siteState.value!!.choices[SitePermissionKind.NOTIFICATIONS])
                assertEquals(false, browser.permissions.siteState.value!!.systemGranted[SitePermissionKind.NOTIFICATIONS])

                tapRequest(); SystemClock.sleep(750)
                assertFalse("A cached system denial must not open repeated permission dialogs", automation.rootInActiveWindow?.packageName?.toString() in owners)
                assertNull(browser.permissions.prompt.value)
                val beforeResume = browser.permissions.siteState.value!!.token
                compose.runOnIdle { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                var enabled = false
                compose.waitUntil(10000) {
                    val root = automation.rootInActiveWindow
                    if (root?.packageName?.toString() == "com.android.settings") {
                        for (viewId in listOf("com.android.settings:id/switch_widget", "android:id/switch_widget", "com.android.settings:id/main_switch_widget")) {
                            root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull { it.isEnabled && !it.isChecked }?.let {
                                var clickable: AccessibilityNodeInfo? = it
                                repeat(5) {
                                    if (!enabled && clickable?.isClickable == true) enabled = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                                    if (!enabled) clickable = clickable?.parent
                                }
                            }
                            if (enabled) break
                        }
                    }
                    enabled
                }
                compose.waitUntil(5000) { context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
                val backTime = SystemClock.uptimeMillis()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    assertTrue(automation.injectInputEvent(KeyEvent(backTime, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK,
                        0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), true))
                }
                compose.waitUntil(10000) { browser.permissions.siteState.value?.let { it.token != beforeResume && !it.busy && it.systemGranted[SitePermissionKind.NOTIFICATIONS] == true } == true }
                tapRequest(); title(id, "Notify granted 1")
                compose.waitUntil(5000) { posted().size == 1 }
                assertNull("Remembered site consent does not need another site prompt", browser.permissions.prompt.value)
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = oldAccessibilityFlags }
                automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
                compose.runOnIdle { browser.permissions.prompt.value?.let { browser.permissions.dismiss(it.token) }; browser.webNotifications.cancelAll(); tabs.closeCreatedTabs(browser) }
                assertTrue(runBlocking { browser.clearBrowsingData(BrowsingDataClearRequest(setOf(BrowsingDataType.SITE_SETTINGS), BrowsingDataRange.ALL_TIME)) }.successful)
            }
        }
    }

    private fun tapRequest() {
        fun find(node: AccessibilityNodeInfo?, depth: Int): Rect? {
            if (node == null || depth > 40) return null
            if (node.isVisibleToUser && (node.text?.toString().orEmpty() + node.contentDescription?.toString().orEmpty()).contains("Request notification")) {
                val rect = Rect(); node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) return rect
            }
            for (index in 0 until node.childCount) find(node.getChild(index), depth + 1)?.let { return it }
            return null
        }
        var bounds: Rect? = null
        compose.waitUntil(8000) { bounds = find(automation.rootInActiveWindow, 0); bounds != null }
        val rect = bounds!!; val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
    }
}
