// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.data

import androidx.datastore.preferences.core.preferencesOf
import app.miru.common.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryZoomExitTest {
    @Test fun webZoomClampsWithinSupportedRangeAndDefaultsTo100() {
        assertEquals(100, (null as Int? ?: 100).coerceIn(50, 200))
        assertEquals(50, 20.coerceIn(50, 200))
        assertEquals(200, 300.coerceIn(50, 200))
        val key = SettingsRepository.webZoomKey
        assertEquals(50, preferencesOf(key to 10)[key]?.coerceIn(50, 200))
        assertEquals(200, preferencesOf(key to 250)[key]?.coerceIn(50, 200))
    }

    @Test fun clearOnExitTypesRoundTripAndPreserveEmptySelection() {
        assertEquals(defaultClearOnExitTypes, SettingsRepository.decodeClearTypes(null))
        assertTrue(SettingsRepository.decodeClearTypes("").isEmpty())
        val selected = setOf(BrowsingDataType.COOKIES, BrowsingDataType.HISTORY, BrowsingDataType.SITE_DATA)
        val encoded = SettingsRepository.encodeClearTypes(selected)
        assertEquals(selected, SettingsRepository.decodeClearTypes(encoded))
    }

    @Test fun invalidCustomSearchFallsBackToGooglePreferenceRule() {
        val prefs = preferencesOf(
            SettingsRepository.searchKey to SearchEngine.CUSTOM.name,
            SettingsRepository.customSearchKey to "ftp://invalid.example/?q=%s",
        )
        assertNull(prefs[SettingsRepository.customSearchKey]?.takeIf { it.startsWith("https://") })
        assertFalse(SettingsRepository.decodeClearTypes("UNKNOWN_TYPE,COOKIES").contains(BrowsingDataType.HISTORY))
        assertEquals(setOf(BrowsingDataType.COOKIES), SettingsRepository.decodeClearTypes("UNKNOWN_TYPE,COOKIES"))
    }
}
