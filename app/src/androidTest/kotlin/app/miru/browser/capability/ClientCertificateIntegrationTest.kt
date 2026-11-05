// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.capability

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
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
 * No certificate/key or native result is injected. This capability class is excluded from Routine.
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

    private fun fixture(block: () -> Unit) {
        compose.waitUntil(30000) { browser.state.value.ready }
        val tabs = BrowserTabFixture(); compose.runOnIdle { tabs.capture(browser) }
        try { block() }
        finally { compose.runOnIdle { browser.prompts.prompt.value?.let { browser.prompts.dismiss(it.token) }; tabs.closeCreatedTabs(browser) } }
    }
}
