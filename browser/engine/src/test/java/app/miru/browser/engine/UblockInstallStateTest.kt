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
}
