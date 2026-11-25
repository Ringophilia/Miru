// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import org.junit.Assert.*
import org.junit.Test

class ClientCertificatePickerGateTest {
    @Test fun aDelayedResultCannotConsumeTheNextRequestsChooser() {
        val gate = ClientCertificatePickerGate()
        assertTrue(gate.begin(1))
        assertFalse(gate.begin(2))
        assertFalse(gate.complete(2))
        assertTrue(gate.busy)
        assertTrue(gate.complete(1))
        assertTrue(gate.begin(2))
        assertFalse(gate.complete(1))
        assertTrue(gate.busy)
        assertTrue(gate.complete(2))
        assertFalse(gate.busy)
    }
    @Test fun aDuplicateCallbackDoesNotFreeOrResolveAnotherRequest() {
        val gate = ClientCertificatePickerGate()
        assertTrue(gate.begin(10))
        assertTrue(gate.complete(10))
        assertFalse(gate.complete(10))
        assertTrue(gate.begin(11))
        assertFalse(gate.complete(10))
        assertFalse(gate.begin(12))
    }
}
