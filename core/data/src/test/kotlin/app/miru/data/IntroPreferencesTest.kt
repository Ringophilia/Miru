// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.*
import org.junit.Test

class IntroPreferencesTest {
    @Test fun freshInstallNeedsTheIntro() { assertFalse(emptyPreferences().hasCompletedIntro()) }
    @Test fun upgradePreservesExistingUsersChoicesWithoutReintroducingOnboarding() {
        val theme = stringPreferencesKey("appearance.theme")
        val old = preferencesOf(theme to "DARK")
        assertTrue(old.hasCompletedIntro())
        assertEquals("DARK", old[theme])
    }
    @Test fun explicitCompletionMarkerWinsOverMigrationHeuristic() {
        val theme = stringPreferencesKey("appearance.theme")
        assertFalse(preferencesOf(introCompletedKey to false, theme to "DARK").hasCompletedIntro())
        assertTrue(preferencesOf(introCompletedKey to true).hasCompletedIntro())
    }
}
