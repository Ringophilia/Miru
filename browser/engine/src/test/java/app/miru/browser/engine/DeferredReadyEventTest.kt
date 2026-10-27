// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import org.junit.Assert.*
import org.junit.Test

class DeferredReadyEventTest {
    @Test fun installationWithoutAReadyEventCannotBecomeReady() {
        assertNull(DeferredReadyEvent<Any>("expected").take())
    }

    @Test fun handoffKeepsTheActualEventObject() {
        val event = Any()
        val pending = DeferredReadyEvent<Any>("expected")
        pending.ready("expected", event)
        assertSame(event, pending.take())
    }

    @Test fun anotherAddonCannotSupplyReadiness() {
        val pending = DeferredReadyEvent<Any>("expected")
        pending.ready("other", Any())
        assertNull(pending.take())
    }

    @Test fun disableOrUninstallInvalidatesAnEarlierReady() {
        val pending = DeferredReadyEvent<Any>("expected")
        pending.ready("expected", Any())
        pending.invalidated("expected")
        assertNull(pending.take())
    }

    @Test fun aNewActualReadyAfterReloadReplacesTheOldEvent() {
        val pending = DeferredReadyEvent<Any>("expected")
        pending.ready("expected", Any())
        pending.invalidated("expected")
        val restarted = Any()
        pending.ready("expected", restarted)
        pending.invalidated("other")
        assertSame(restarted, pending.take())
    }

    @Test fun handoffCannotBeRepeatedOrResurrectedByALateEvent() {
        val pending = DeferredReadyEvent<Any>("expected")
        assertNull(pending.take())
        pending.ready("expected", Any())
        pending.invalidated("expected")
        try { pending.take(); fail("A second handoff must not deliver another event") }
        catch(_: IllegalStateException) { }
    }

    @Test fun processFailureInvalidatesReadinessAndSurvivesALateReady() {
        val pending = DeferredReadyEvent<Any>("expected")
        pending.ready("expected", Any())
        pending.processDisabled()
        pending.ready("expected", Any())
        assertTrue(pending.processUnavailable)
        assertNull(pending.take())
    }
}
