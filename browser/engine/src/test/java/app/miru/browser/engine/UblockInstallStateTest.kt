// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import org.junit.Assert.*
import org.junit.Test

/** Deterministic ordering coverage for the state that guards the real Gecko operations. */
class UblockInstallStateTest {
    private val enabled = UblockInstallState.Installed("1.75.0", enabled = true, privateAllowed = true)

    @Test fun `deadline retains native slot and genuine late ready continues through permission setup`() {
        val state = UblockInstallState(true)
        val attempt = state.beginAttempt()!!
        assertTrue(state.startNativeInstall(attempt))
        assertTrue(state.deadlineExceeded(attempt))
        assertEquals(AdblockStatus.FAILED, state.status)
        assertNull("Retry must not start a concurrent install", state.beginAttempt())
        assertTrue(state.nativeInstallPending)

        val notPrivate = enabled.copy(privateAllowed = false)
        assertFalse(state.observeReady(notPrivate))
        assertEquals(AdblockStatus.INSTALLING, state.status)
        assertTrue(state.finishNativeInstall(attempt))
        assertFalse("Native ready alone cannot skip the privacy setup", state.finishSetup(attempt))
        state.invalidateReady(notPrivate.version)
        state.observeInstalled(enabled)
        assertTrue(state.finishSetup(attempt))
        assertEquals("Reload after changing private permission needs its own ready event", AdblockStatus.INSTALLING, state.status)
        state.observeReady(enabled)
        assertEquals(AdblockStatus.READY, state.status)
        assertNull(state.error)
    }

    @Test fun `repeated retries and deadlines do not release an unfinished native operation`() {
        val state = UblockInstallState(true)
        val attempt = state.beginAttempt()!!
        state.startNativeInstall(attempt)
        repeat(4) {
            assertTrue(state.deadlineExceeded(attempt))
            assertNull(state.beginAttempt())
            assertEquals(attempt, state.activeAttempt)
            assertTrue(state.nativeInstallPending)
        }
        state.finishNativeInstall(attempt)
        state.observeReady(enabled)
        assertTrue(state.finishSetup(attempt))
        assertFalse(state.deadlineExceeded(attempt))
        assertEquals(AdblockStatus.READY, state.status)
    }

    @Test fun `disable during late install is applied before completion can report protection`() {
        val state = UblockInstallState(true)
        val attempt = state.beginAttempt()!!
        state.startNativeInstall(attempt)
        state.deadlineExceeded(attempt)
        state.setDesiredEnabled(false)
        state.observeReady(enabled)
        state.finishNativeInstall(attempt)
        assertFalse(state.finishSetup(attempt))
        assertEquals(AdblockStatus.INSTALLING, state.status)
        state.invalidateReady(enabled.version)
        state.observeInstalled(enabled.copy(enabled = false))
        assertTrue(state.finishSetup(attempt))
        assertEquals(AdblockStatus.DISABLED, state.status)

        assertTrue("A late enabled event must be reconciled to the user's disabled setting", state.observeReady(enabled))
        assertNotEquals(AdblockStatus.READY, state.status)
        val reconcile = state.beginAttempt()!!
        state.startSetup(reconcile)
        state.observeInstalled(enabled.copy(enabled = false))
        assertTrue(state.finishSetup(reconcile))
        assertEquals(AdblockStatus.DISABLED, state.status)
    }

    @Test fun `latest setting wins when toggled while native enable callback is pending`() {
        val state = UblockInstallState(false)
        val attempt = state.beginAttempt()!!
        state.startSetup(attempt)
        state.observeInstalled(enabled.copy(enabled = false))
        state.setDesiredEnabled(true)
        state.invalidateReady(enabled.version)
        state.setDesiredEnabled(false)
        state.observeReady(enabled)
        assertFalse(state.finishSetup(attempt))
        assertNotEquals(AdblockStatus.READY, state.status)
        state.observeInstalled(enabled.copy(enabled = false))
        assertTrue(state.finishSetup(attempt))
        assertEquals(AdblockStatus.DISABLED, state.status)
    }

    @Test fun `genuine ready after native error requests installed-list reconciliation`() {
        val state = UblockInstallState(true)
        val failed = state.beginAttempt()!!
        state.startNativeInstall(failed)
        assertTrue(state.fail(failed, "NativeInstallException"))
        assertEquals(AdblockStatus.FAILED, state.status)
        assertTrue(state.observeReady(enabled))
        assertEquals("Ready cannot clear a native error until setup has succeeded", AdblockStatus.FAILED, state.status)
        val recovery = state.beginAttempt()!!
        assertFalse("An error from the old attempt must not fail the new one", state.fail(failed, "LateInstallException"))
        state.startSetup(recovery)
        state.observeInstalled(enabled)
        assertTrue(state.finishSetup(recovery))
        assertEquals(AdblockStatus.READY, state.status)
        assertFalse("Duplicate ready must not start another operation", state.observeReady(enabled))
        assertFalse(state.finishNativeInstall(failed))
        assertFalse(state.fail(failed, "LateInstallException"))
        assertEquals(AdblockStatus.READY, state.status)
    }

    @Test fun `private permission error survives duplicate ready and requires an explicit retry`() {
        val state = UblockInstallState(true)
        val attempt = state.beginAttempt()!!
        val notPrivate = enabled.copy(privateAllowed = false)
        state.observeReady(notPrivate)
        state.startSetup(attempt)
        assertTrue(state.fail(attempt, "PrivatePermissionException"))
        repeat(3) { assertFalse(state.observeReady(notPrivate)) }
        assertFalse(state.needsReconciliation)
        assertEquals("PrivatePermissionException", state.error)
        assertEquals(AdblockStatus.FAILED, state.status)
        val retry = state.beginAttempt()!!
        state.startSetup(retry)
        assertFalse(state.finishSetup(retry))
        state.invalidateReady(enabled.version)
        state.observeInstalled(enabled)
        state.observeReady(enabled)
        assertTrue(state.finishSetup(retry))
        assertEquals(AdblockStatus.READY, state.status)
    }

    @Test fun `installed extension found during a list still requires genuine ready`() {
        val state = UblockInstallState(true)
        val attempt = state.beginAttempt()!!
        state.observeInstalled(enabled)
        state.startSetup(attempt)
        assertTrue(state.finishSetup(attempt))
        assertEquals(AdblockStatus.INSTALLING, state.status)
        assertFalse(state.observeReady(enabled))
        assertEquals(AdblockStatus.READY, state.status)
    }

    @Test fun `ready observed during list can be configured without a second installation`() {
        val state = UblockInstallState(true)
        val attempt = state.beginAttempt()!!
        state.observeReady(enabled)
        assertFalse(state.nativeInstallPending)
        state.startSetup(attempt)
        assertTrue(state.finishSetup(attempt))
        assertEquals(AdblockStatus.READY, state.status)
        assertNull(state.error)
    }

    @Test fun `disabled process spawning retires every previously ready version`() {
        val state = UblockInstallState(true)
        val attempt = state.beginAttempt()!!
        state.observeReady(enabled.copy(version = "1.74.0"))
        state.observeReady(enabled)
        assertTrue(state.finishSetup(attempt))
        assertEquals(AdblockStatus.READY, state.status)

        state.extensionProcessUnavailable()

        assertTrue(state.extensionProcessBlocked)
        assertFalse(state.processRestartRequested)
        assertFalse(state.hasReadyVersion("1.74.0"))
        assertFalse(state.hasReadyVersion(enabled.version))
        assertFalse(state.setupFinished)
        assertEquals(UblockInstallState.PROCESS_UNAVAILABLE_ERROR, state.error)
        assertEquals(AdblockStatus.FAILED, state.status)
    }

    @Test fun `metadata completion releases its attempt without clearing the process block`() {
        val state = UblockInstallState(true)
        state.extensionProcessUnavailable()
        repeat(2) {
            val attempt = state.beginAttempt()!!
            assertEquals(UblockInstallState.PROCESS_UNAVAILABLE_ERROR, state.error)
            assertEquals(AdblockStatus.FAILED, state.status)
            state.observeInstalled(enabled)
            assertTrue(state.startSetup(attempt))
            assertTrue("Metadata setup must end rather than loop forever", state.finishSetup(attempt))
            assertTrue(state.setupFinished)
            assertNull(state.activeAttempt)
            assertNull(state.phase)
            assertTrue(state.extensionProcessBlocked)
            assertFalse(state.processRestartRequested)
            assertFalse(state.hasReadyVersion(enabled.version))
            assertEquals(UblockInstallState.PROCESS_UNAVAILABLE_ERROR, state.error)
            assertEquals(AdblockStatus.FAILED, state.status)
        }
    }

    @Test fun `unsolicited late ready neither updates metadata nor starts automatic recovery while blocked`() {
        val state = UblockInstallState(true)
        val beforeFailure = enabled.copy(privateAllowed = false)
        state.observeInstalled(beforeFailure)
        state.extensionProcessUnavailable()

        repeat(3) {
            assertFalse(state.observeReady(enabled))
            assertEquals(beforeFailure, state.installed)
            assertFalse(state.hasReadyVersion(enabled.version))
            assertFalse(state.needsReconciliation)
            assertNull(state.activeAttempt)
            assertTrue(state.extensionProcessBlocked)
            assertFalse(state.processRestartRequested)
            assertEquals(UblockInstallState.PROCESS_UNAVAILABLE_ERROR, state.error)
            assertEquals(AdblockStatus.FAILED, state.status)
        }
    }

    @Test fun `restart intent requires a blocked process and does not itself prove readiness`() {
        val state = UblockInstallState(true)
        assertFalse(state.requestExtensionProcessRestart())
        assertFalse(state.processRestartRequested)
        state.extensionProcessUnavailable()
        val firstAttempt = state.beginAttempt()!!
        assertTrue(state.requestExtensionProcessRestart())
        assertTrue(state.extensionProcessBlocked)
        assertTrue(state.processRestartRequested)
        assertFalse(state.hasReadyVersion(enabled.version))
        assertEquals(AdblockStatus.FAILED, state.status)
        assertTrue(state.fail(firstAttempt, "InstalledListException"))
        assertTrue(state.extensionProcessBlocked)
        assertTrue(state.processRestartRequested)
        val attempt = state.beginAttempt()!!
        assertTrue("A later explicit retry must not be blocked by the earlier request", state.requestExtensionProcessRestart())
        assertEquals(UblockInstallState.PROCESS_UNAVAILABLE_ERROR, state.error)
        state.observeInstalled(enabled)
        assertTrue(state.finishSetup(attempt))
        assertEquals("Listed metadata after restart is still not a native ready event", AdblockStatus.FAILED, state.status)

        assertFalse(state.observeReady(enabled))
        assertFalse(state.extensionProcessBlocked)
        assertFalse(state.processRestartRequested)
        assertTrue(state.hasReadyVersion(enabled.version))
        assertNull(state.error)
        assertEquals(AdblockStatus.READY, state.status)
        assertFalse(state.requestExtensionProcessRestart())
    }

    @Test fun `real ready after an explicit restart still requires complete privacy setup`() {
        val state = UblockInstallState(true)
        state.extensionProcessUnavailable()
        val attempt = state.beginAttempt()!!
        assertTrue(state.requestExtensionProcessRestart())
        state.observeReady(enabled.copy(privateAllowed = false))

        assertFalse(state.extensionProcessBlocked)
        assertFalse(state.processRestartRequested)
        assertNull(state.error)
        assertFalse(state.finishSetup(attempt))
        assertEquals(AdblockStatus.INSTALLING, state.status)
        state.invalidateReady(enabled.version)
        state.observeInstalled(enabled)
        assertTrue(state.finishSetup(attempt))
        assertEquals("Changing private permission must await its own real ready", AdblockStatus.INSTALLING, state.status)
        state.observeReady(enabled)
        assertEquals(AdblockStatus.READY, state.status)
    }

    @Test fun `another process failure revokes an earlier restart request and ready event`() {
        val state = UblockInstallState(true)
        state.extensionProcessUnavailable()
        assertTrue(state.requestExtensionProcessRestart())
        state.extensionProcessUnavailable()

        assertFalse(state.processRestartRequested)
        assertFalse(state.observeReady(enabled))
        assertTrue(state.extensionProcessBlocked)
        assertFalse(state.hasReadyVersion(enabled.version))
        assertEquals(AdblockStatus.FAILED, state.status)
        val attempt = state.beginAttempt()!!
        assertTrue(state.requestExtensionProcessRestart())
        state.observeReady(enabled)
        assertTrue(state.finishSetup(attempt))
        assertEquals(AdblockStatus.READY, state.status)
    }

    @Test fun `process failure and deadlines keep the unfinished native install occupied`() {
        val state = UblockInstallState(true)
        val attempt = state.beginAttempt()!!
        assertTrue(state.startNativeInstall(attempt))
        state.extensionProcessUnavailable()

        repeat(3) {
            assertTrue(state.deadlineExceeded(attempt))
            assertNull(state.beginAttempt())
            assertEquals(attempt, state.activeAttempt)
            assertTrue(state.nativeInstallPending)
            assertFalse(state.observeReady(enabled))
            assertEquals(UblockInstallState.PROCESS_UNAVAILABLE_ERROR, state.error)
            assertEquals(AdblockStatus.FAILED, state.status)
        }
        assertTrue(state.finishNativeInstall(attempt))
        state.observeInstalled(enabled)
        assertTrue(state.finishSetup(attempt))
        assertNull(state.activeAttempt)
        assertFalse(state.nativeInstallPending)
        assertEquals(AdblockStatus.FAILED, state.status)
        assertTrue(state.extensionProcessBlocked)
        assertFalse(state.processRestartRequested)
    }

    @Test fun `ready after a requested process restart does not clear an unrelated configuration failure`() {
        val state = UblockInstallState(true)
        state.extensionProcessUnavailable()
        val attempt = state.beginAttempt()!!
        assertTrue(state.requestExtensionProcessRestart())
        assertTrue(state.startSetup(attempt))
        assertTrue(state.fail(attempt, "PrivatePermissionException"))

        assertFalse(state.observeReady(enabled))
        assertFalse(state.extensionProcessBlocked)
        assertFalse(state.processRestartRequested)
        assertEquals("PrivatePermissionException", state.error)
        assertFalse(state.needsReconciliation)
        assertEquals(AdblockStatus.FAILED, state.status)
        val retry = state.beginAttempt()!!
        assertTrue(state.startSetup(retry))
        state.observeInstalled(enabled)
        assertTrue(state.finishSetup(retry))
        assertEquals(AdblockStatus.READY, state.status)
    }

    @Test fun `disabled metadata cannot hide the process block as an ordinary disabled setting`() {
        val state = UblockInstallState(false)
        state.extensionProcessUnavailable()
        val attempt = state.beginAttempt()!!
        state.observeInstalled(enabled.copy(enabled = false))
        assertTrue(state.finishSetup(attempt))

        assertEquals(AdblockStatus.FAILED, state.status)
        assertTrue(state.extensionProcessBlocked)
        assertEquals(UblockInstallState.PROCESS_UNAVAILABLE_ERROR, state.error)
    }

    @Test fun `an early real ready version cannot overwrite the current listed enabled and private flags`() {
        val state = UblockInstallState(true)
        val attempt = state.beginAttempt()!!
        val listed = enabled.copy(enabled = false, privateAllowed = false)
        state.observeInstalled(listed)

        assertTrue(state.recordGenuineReadyVersion(enabled.version))

        assertSame("Version evidence must leave the actual current metadata object untouched", listed, state.installed)
        assertFalse(state.installed!!.enabled)
        assertFalse(state.installed!!.privateAllowed)
        assertTrue(state.hasReadyVersion(enabled.version))
        assertFalse(state.finishSetup(attempt))
        assertNotEquals(AdblockStatus.READY, state.status)
    }

    @Test fun `an old genuine ready version plus a newer installed list is not a new version ready event`() {
        val state = UblockInstallState(true)
        val attempt = state.beginAttempt()!!
        assertTrue(state.recordGenuineReadyVersion("1.74.0"))
        assertNull("An early ready version does not invent installed metadata", state.installed)
        assertFalse(state.finishSetup(attempt))

        state.observeInstalled(enabled)
        assertTrue(state.finishSetup(attempt))
        assertTrue(state.hasReadyVersion("1.74.0"))
        assertFalse(state.hasReadyVersion(enabled.version))
        assertEquals("Listing the new version cannot supply its missing ready event", AdblockStatus.INSTALLING, state.status)

        assertTrue(state.recordGenuineReadyVersion(enabled.version))
        assertSame(enabled, state.installed)
        assertEquals(AdblockStatus.READY, state.status)
    }
}
