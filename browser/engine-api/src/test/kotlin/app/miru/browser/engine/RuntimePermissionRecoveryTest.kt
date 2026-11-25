// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import org.junit.Assert.*
import org.junit.Test

class RuntimePermissionRecoveryTest {
    @Test fun firstCancellationDoesNotSuggestSettingsEvenWhenAndroidReportsDenial() {
        val recovery = RuntimePermissionRecovery()
        val denied = mapOf("camera" to false)
        assertFalse(recovery.requiresSettings(denied, emptySet(), emptySet()))
        assertFalse(recovery.requiresSettings(denied, emptySet(), setOf("camera")))
        assertFalse(recovery.requiresSettings(emptyMap(), setOf("camera"), emptySet()))
    }

    @Test fun confirmedBlockCanBeRecoveredThroughSettingsAndDoesNotAffectAnotherPermission() {
        val recovery = RuntimePermissionRecovery()
        val cameraDenied = mapOf("camera" to false)
        assertTrue(recovery.requiresSettings(cameraDenied, setOf("camera"), emptySet()))
        assertTrue(recovery.requiresSettings(cameraDenied, emptySet(), emptySet()))
        assertFalse(recovery.requiresSettings(mapOf("microphone" to false), emptySet(), emptySet()))
        recovery.clearGranted { it == "camera" }
        assertFalse(recovery.requiresSettings(cameraDenied, emptySet(), emptySet()))
    }
}
