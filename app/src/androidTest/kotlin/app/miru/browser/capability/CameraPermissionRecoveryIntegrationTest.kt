// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.capability

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.browser.BrowserTabFixture
import app.miru.browser.MainActivity
import app.miru.browser.MiruApplication
import app.miru.browser.R
import app.miru.browser.RuntimePermissionOrigin
import app.miru.browser.engine.SitePermissionChoice
import app.miru.browser.engine.SitePermissionKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a fresh English API 35 Android user. All permission changes use Android UI. */
@RunWith(AndroidJUnit4::class)
class CameraPermissionRecoveryIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val activity get() = compose.activity
    private val container get() = (activity.application as MiruApplication).container
    private val browser get() = container.browser
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val controllers = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
    private val settingsWindows = controllers + "com.android.settings"

    @Test fun permanentAndroidDenialCanBeRecoveredThroughSettingsWithoutGrantingTheWebsite() {
        assertEquals(35, Build.VERSION.SDK_INT)
        assertFalse("Camera permission must start ungranted", cameraGranted())
        val skip = activity.getString(R.string.intro_skip)
        compose.waitUntil(15000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText(skip).performClick()
        compose.waitUntil(30000) { browser.state.value.ready }
        val tabs = BrowserTabFixture()
        compose.runOnIdle { tabs.capture(browser) }
        val oldFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        try {
            RuntimePermissionOrigin().use { origin ->
                var id = ""
                compose.runOnIdle { id = browser.newTab("${origin.url}/camera") }
                title(id, "Runtime ready")
                denyCamera()
                title(id, "Runtime denied")
                compose.onNodeWithText(activity.getString(R.string.site_permission_settings_title)).assertDoesNotExist()

                denyCamera()
                title(id, "Runtime denied")
                compose.waitUntil(8000) { activity.sitePermissionSettingsRequired }
                assertFalse(cameraGranted())
                assertFalse(activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
                assertNull(browser.permissions.prompt.value)
                assertCameraChoice(id, SitePermissionChoice.ASK, false)
                compose.onNodeWithText(activity.getString(R.string.site_permission_settings)).performClick()

                clickSettingsText(setOf("Permissions", "App permissions"))
                clickSettingsText(setOf("Camera"))
                clickSettingsText(setOf("Allow only while using the app"))
                compose.waitUntil(8000) { cameraGranted() }
                repeat(3) {
                    if (automation.rootInActiveWindow?.packageName?.toString() in settingsWindows) {
                        pressBack()
                        SystemClock.sleep(350)
                    }
                }
                compose.waitUntil(10000) { automation.rootInActiveWindow?.packageName?.toString() == activity.packageName }
                assertCameraChoice(id, SitePermissionChoice.ASK, true)
                tapWebRequest()
                compose.waitUntil(10000) { browser.permissions.prompt.value?.kinds == setOf(SitePermissionKind.CAMERA) }
                compose.onNodeWithText(activity.getString(R.string.site_permission_allow)).performClick()
                title(id, "Runtime live video")
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            compose.runOnIdle { tabs.closeCreatedTabs(browser) }
        }
    }

    private fun cameraGranted() = activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun denyCamera() {
        tapWebRequest()
        compose.waitUntil(10000) { browser.permissions.runtimeRequest.value?.launched == true }
        assertEquals(listOf(Manifest.permission.CAMERA), browser.permissions.runtimeRequest.value!!.permissions)
        val denialIds = setOf(
            "permission_deny_button",
            "permission_deny_dont_ask_again_button",
            "permission_deny_and_dont_ask_again_button",
        )
        var controls = emptyList<String>()
        try {
            compose.waitUntil(10000) {
                val root = automation.rootInActiveWindow ?: return@waitUntil false
                if (root.packageName?.toString() !in controllers) return@waitUntil false
                val nodes = descendants(root).filter { it.isVisibleToUser && it.isEnabled }.toList()
                controls = nodes.mapNotNull { it.viewIdResourceName }.distinct()
                nodes.any { node ->
                    node.viewIdResourceName?.substringAfterLast('/') in denialIds &&
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Android camera denial button unavailable. Controls: $controls", failure)
        }
        compose.waitUntil(10000) { browser.permissions.runtimeRequest.value == null }
    }

    private fun assertCameraChoice(id: String, choice: SitePermissionChoice, systemAllowed: Boolean) {
        compose.runOnIdle { browser.permissions.refreshSite() }
        compose.waitUntil(10000) { browser.permissions.siteState.value?.let { it.tabId == id && !it.busy && !it.error } == true }
        val site = checkNotNull(browser.permissions.siteState.value)
        assertEquals(choice, site.choices[SitePermissionKind.CAMERA])
        assertEquals(systemAllowed, site.systemGranted[SitePermissionKind.CAMERA])
    }

    private fun title(id: String, expected: String) {
        compose.waitUntil(20000) { browser.state.value.tabs.any { it.id == id && it.title == expected && !it.loading } }
    }

    private fun clickSettingsText(labels: Set<String>) {
        var screenLabels = emptyList<String>()
        try {
            compose.waitUntil(10000) {
                val root = automation.rootInActiveWindow ?: return@waitUntil false
                if (root.packageName?.toString() !in settingsWindows) return@waitUntil false
                val nodes = descendants(root).filter { it.isVisibleToUser }.toList()
                screenLabels = nodes.mapNotNull { it.text?.toString() }.distinct().take(20)
                nodes.filter { it.text?.toString() in labels }.any { node ->
                    generateSequence(node) { it.parent }.take(5).any { it.isEnabled && it.isClickable && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                }
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Android settings option $labels unavailable. Visible labels: $screenLabels", failure)
        }
    }

    private fun descendants(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(descendants(it)) }
    }

    private fun tapWebRequest() {
        var bounds: Rect? = null
        compose.waitUntil(8000) {
            val root = automation.rootInActiveWindow ?: return@waitUntil false
            val button = descendants(root).firstOrNull { it.isVisibleToUser &&
                (it.text?.toString() == "Request permission" || it.contentDescription?.toString() == "Request permission") }
            bounds = button?.let { Rect().also(it::getBoundsInScreen) }
            bounds?.isEmpty == false
        }
        val target = checkNotNull(bounds)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.exactCenterX(), target.exactCenterY(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
    }

    private fun pressBack() {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            assertTrue(automation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK,
                0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), true))
        }
    }
}
