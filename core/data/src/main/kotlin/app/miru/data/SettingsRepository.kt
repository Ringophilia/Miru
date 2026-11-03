// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.miru.common.SearchEngine
import app.miru.common.AccentColor
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.emptyPreferences

private val Context.miruPreferences by preferencesDataStore(name = "miru_settings")
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ToolbarPosition { TOP, BOTTOM }
data class BrowserSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    // Off by default so the brand palette (matching the launcher icon) is what people see first.
    val dynamicColor: Boolean = false,
    val accent: AccentColor = AccentColor.RINGO_RED,
    val toolbarPosition: ToolbarPosition = ToolbarPosition.TOP,
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val searchSuggestions: Boolean = true,
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.miruPreferences
    private val themeKey = stringPreferencesKey("appearance.theme")
    private val dynamicKey = booleanPreferencesKey("appearance.dynamicColor")
    private val accentKey = stringPreferencesKey("appearance.accent")
    private val positionKey = stringPreferencesKey("appearance.toolbarPosition")
    private val searchKey = stringPreferencesKey("search.engine")
    private val suggestionsKey = booleanPreferencesKey("search.suggestions")
    val settings: Flow<BrowserSettings> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { values ->
        BrowserSettings(
            theme = enumValue<ThemeMode>(values[themeKey]) ?: ThemeMode.SYSTEM,
            dynamicColor = values[dynamicKey] ?: false,
            accent = AccentColor.fromPreference(values[accentKey]),
            toolbarPosition = enumValue<ToolbarPosition>(values[positionKey]) ?: ToolbarPosition.TOP,
            searchEngine = enumValue<SearchEngine>(values[searchKey]) ?: SearchEngine.GOOGLE,
            searchSuggestions = values[suggestionsKey] ?: true,
        )
    }
    suspend fun setTheme(value: ThemeMode) { store.edit { it[themeKey] = value.name } }
    suspend fun setDynamicColor(value: Boolean) { store.edit { it[dynamicKey] = value } }
    suspend fun setAccent(value: AccentColor) { store.edit { it[accentKey] = value.preferenceValue } }
    suspend fun setToolbarPosition(value: ToolbarPosition) { store.edit { it[positionKey] = value.name } }
    suspend fun setSearchEngine(value: SearchEngine) { store.edit { it[searchKey] = value.name } }
    suspend fun setSearchSuggestions(value: Boolean) { store.edit { it[suggestionsKey] = value } }

    private inline fun <reified T : Enum<T>> enumValue(value: String?): T? = enumValues<T>().firstOrNull { it.name == value }
}
