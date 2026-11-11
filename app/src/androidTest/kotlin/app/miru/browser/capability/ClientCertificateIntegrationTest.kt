// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.capability

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import android.security.KeyChain
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.browser.BrowserTabFixture
import app.miru.browser.MainActivity
import app.miru.browser.MiruApplication
import app.miru.browser.R
import app.miru.browser.engine.BrowserPromptKind
import app.miru.browser.engine.BrowserPromptState
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run each method in a fresh application process: TLS may remember a prior no-certificate choice.
 * The successful-authentication case installs a public test identity in a disposable user.
 * This capability class is excluded from Routine.
 */
@RunWith(AndroidJUnit4::class)
class ClientCertificateIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity
    private val browser get() = (context.application as MiruApplication).container.browser
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun request(private: Boolean = false): BrowserPromptState {
        var id = ""
        compose.runOnIdle { id = browser.newTab("https://client.badssl.com/", isPrivate = private) }
        compose.waitUntil(45000) { browser.prompts.prompt.value?.let { it.tabId == id && it.kind == BrowserPromptKind.CERTIFICATE } == true }
        return browser.prompts.prompt.value!!
    }

    @Test fun aRealCertificateChallengeCanBeCancelledInTheBrowser() = fixture {
        val state = request()
        assertTrue(state.certificateHost.contains("client.badssl.com"))
        compose.onNodeWithText(context.getString(R.string.client_certificate_title)).assertExists()
        compose.onNodeWithTag("page_prompt_cancel").performClick()
        compose.waitUntil(5000) { browser.prompts.prompt.value?.token != state.token }
        assertNull(browser.prompts.prepareClientCertificate(state.token))
    }

    @Test fun systemPickerCancellationOrNoMatchingCertificateCompletesTheRealRequest() = fixture {
        val state = request()
        runBlocking {
            val completed = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { browser.prompts.clientCertificateResults.first { it.token == state.token } }
            compose.onNodeWithTag("page_prompt_confirm").performClick()
            // Android may suppress the picker when no matching identity is installed.
            // If shown, always cancel; never select an existing user identity.
            var pickerSeen = false
            compose.waitUntil(15000) {
                val root = automation.rootInActiveWindow
                if (root?.packageName?.toString() in setOf("com.android.keychain", "com.android.certinstaller")) {
                    pickerSeen = true
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                        assertTrue(automation.injectInputEvent(KeyEvent(now, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK,
                            0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), true))
                    }
                }
                completed.isCompleted
            }
            val result = withTimeout(5000) { completed.await() }
            android.util.Log.i("MiruClientCertTest", "systemPickerShown=$pickerSeen selected=${result.selected} unavailable=${result.unavailable}")
            assertFalse("The real KeyChain call must launch or complete normally", result.unavailable)
            assertFalse("Do not choose or send an existing user identity", result.selected)
        }
        compose.waitUntil(5000) { browser.prompts.prompt.value?.token != state.token }
    }

    @Test fun closedPrivateRequestCannotLaunchACertificatePickerForAnotherPage() = fixture {
        assertTrue("Controlled fixture requires no pre-existing private tabs", browser.state.value.tabs.none { it.isPrivate })
        var normal = ""
        compose.runOnIdle { normal = browser.newTab() }
        val state = request(private = true)
        assertTrue(state.isPrivate)
        compose.onNodeWithText(context.getString(R.string.client_certificate_private)).assertExists()
        compose.runOnIdle { browser.closePrivateTabsPermanently() }
        compose.waitUntil(5000) { browser.prompts.prompt.value?.token != state.token }
        compose.runOnIdle { assertNull(browser.prompts.prepareClientCertificate(state.token)) }
        assertEquals(normal, browser.state.value.selectedTabId)
    }

    @Test fun selectingThePublicTestIdentityCompletesClientAuthentication() = fixture {
        check(InstrumentationRegistry.getArguments().getString("miruClientCertificateTest") == "badssl")
        check(!context.getSystemService(UserManager::class.java).isSystemUser) {
            "Use a disposable Android user for the public test identity"
        }
        val skip = context.getString(R.string.intro_skip)
        compose.onAllNodesWithText(skip).fetchSemanticsNodes().firstOrNull()?.let {
            compose.onNodeWithText(skip).performClick()
        }
        installPublicTestIdentity()

        val state = request()
        runBlocking {
            val completed = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                browser.prompts.clientCertificateResults.first { it.token == state.token }
            }
            compose.onNodeWithTag("page_prompt_confirm").performClick()
            actInSystemWindow("com.android.keychain", "test identity") { node ->
                node.text?.toString() == TEST_ALIAS && generateSequence(node) { it.parent }.take(3).any {
                    it.isClickable && it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            confirmSystemDialog("com.android.keychain")
            val result = withTimeout(15000) { completed.await() }
            assertEquals(state.tabId, result.tabId)
            assertTrue("The system picker must return the chosen identity", result.selected)
            assertFalse(result.unavailable)
        }
        compose.waitUntil(25000) {
            browser.state.value.tabs.any {
                it.id == state.tabId && !it.loading && it.secure && it.title == "client.badssl.com"
            }
        }
        actInSystemWindow(context.packageName, "authenticated BadSSL page") { node ->
            node.text?.toString()?.contains("This site requires a") == true
        }
    }

    private fun installPublicTestIdentity() {
        val connection = URI("https://badssl.com/certs/badssl.com-client.p12").toURL().openConnection() as HttpsURLConnection
        val identity = try {
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            check(connection.responseCode == 200) { "Public BadSSL identity download failed" }
            connection.inputStream.use { it.readNBytes(16385) }.also { check(it.size in 1..16384) }
        } finally {
            connection.disconnect()
        }
        val result = AtomicReference<Int>()
        val install = KeyChain.createInstallIntent()
            .putExtra(KeyChain.EXTRA_NAME, TEST_ALIAS)
            .putExtra(KeyChain.EXTRA_PKCS12, identity)
        val launcher = compose.runOnIdle {
            context.activityResultRegistry.register("test-client-identity", ActivityResultContracts.StartActivityForResult()) {
                result.set(it.resultCode)
            }.also { it.launch(install) }
        }
        try {
            val password = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "badssl.com")
            }
            actInSystemWindow("com.android.certinstaller", "PKCS12 password field") { node ->
                node.isPassword && node.isEditable &&
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, password)
            }
            confirmSystemDialog("com.android.certinstaller")
            actInSystemWindow("com.android.certinstaller", "VPN & app user certificate option") { node ->
                node.text?.toString() == "VPN & app user certificate" &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            confirmSystemDialog("com.android.certinstaller")
            actInSystemWindow("com.android.certinstaller", "credential name field") { node ->
                node.isEditable && node.text?.toString() == TEST_ALIAS
            }
            confirmSystemDialog("com.android.certinstaller")
            compose.waitUntil(15000) { result.get() != null }
            assertEquals(Activity.RESULT_OK, result.get())
        } finally {
            compose.runOnIdle { launcher.unregister() }
        }
    }

    private fun confirmSystemDialog(packageName: String) = actInSystemWindow(packageName, "confirmation button") { node ->
        node.text?.toString()?.uppercase() in setOf("OK", "ALLOW", "SELECT") && node.isEnabled &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun actInSystemWindow(packageName: String, description: String, action: (AccessibilityNodeInfo) -> Boolean) {
        fun descendants(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            yield(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(descendants(it)) }
        }
        var visibleLabels = emptyList<String>()
        try {
            compose.waitUntil(15000) {
                val root = automation.rootInActiveWindow ?: return@waitUntil false
                if (root.packageName?.toString() != packageName) return@waitUntil false
                val nodes = descendants(root).filter { it.isVisibleToUser }.toList()
                visibleLabels = nodes.filterNot { it.isPassword }.mapNotNull { it.text?.toString() }.distinct().take(12)
                nodes.any(action)
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Could not complete $description in $packageName. Visible labels: $visibleLabels", failure)
        }
    }

    private fun fixture(block: () -> Unit) {
        compose.waitUntil(30000) { browser.state.value.ready }
        val tabs = BrowserTabFixture(); compose.runOnIdle { tabs.capture(browser) }
        try { block() }
        finally { compose.runOnIdle { browser.prompts.prompt.value?.let { browser.prompts.dismiss(it.token) }; tabs.closeCreatedTabs(browser) } }
    }

    private companion object {
        const val TEST_ALIAS = "Miru BadSSL QA"
    }
}
