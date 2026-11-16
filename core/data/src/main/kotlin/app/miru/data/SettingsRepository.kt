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
    val privateNotificationPrompted: Boolean = false,
    val introCompleted: Boolean = false,
    val security: BrowserSecuritySettings = BrowserSecuritySettings(),
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.miruPreferences
    private val themeKey = stringPreferencesKey("appearance.theme")
    private val dynamicKey = booleanPreferencesKey("appearance.dynamicColor")
    private val accentKey = stringPreferencesKey("appearance.accent")
    private val positionKey = stringPreferencesKey("appearance.toolbarPosition")
    private val searchKey = stringPreferencesKey("search.engine")
    private val suggestionsKey = booleanPreferencesKey("search.suggestions")
    private val privateNotificationKey = booleanPreferencesKey("privacy.privateNotificationPrompted")
    private val thirdPartyKey = booleanPreferencesKey("privacy.blockThirdPartyCookies")
    private val httpsOnlyKey = booleanPreferencesKey("privacy.httpsOnly")
    private val dnsModeKey = stringPreferencesKey("privacy.secureDns.mode")
    private val dnsUrlKey = stringPreferencesKey("privacy.secureDns.customUrl")
    val settings: Flow<BrowserSettings> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { values ->
        val dnsUrl = values[dnsUrlKey]?.let(::validatedSecureDnsUrl)
        val dnsMode = enumValue<SecureDnsMode>(values[dnsModeKey])
            ?.takeIf { it != SecureDnsMode.CUSTOM || dnsUrl != null } ?: SecureDnsMode.AUTOMATIC
        BrowserSettings(
            theme = enumValue<ThemeMode>(values[themeKey]) ?: ThemeMode.SYSTEM,
            dynamicColor = values[dynamicKey] ?: false,
            accent = AccentColor.fromPreference(values[accentKey]),
            toolbarPosition = enumValue<ToolbarPosition>(values[positionKey]) ?: ToolbarPosition.TOP,
            searchEngine = enumValue<SearchEngine>(values[searchKey]) ?: SearchEngine.GOOGLE,
            searchSuggestions = values[suggestionsKey] ?: true,
            privateNotificationPrompted = values[privateNotificationKey] ?: false,
            introCompleted = values.hasCompletedIntro(),
            security = BrowserSecuritySettings(
                blockThirdPartyCookies = values[thirdPartyKey] ?: true,
                httpsOnly = values[httpsOnlyKey] ?: false,
                secureDnsMode = dnsMode,
                customDnsUrl = dnsUrl,
            ),
        )
    }
    suspend fun setTheme(value: ThemeMode) { store.edit { it[themeKey] = value.name } }
    suspend fun setDynamicColor(value: Boolean) { store.edit { it[dynamicKey] = value } }
    suspend fun setAccent(value: AccentColor) { store.edit { it[accentKey] = value.preferenceValue } }
    suspend fun setToolbarPosition(value: ToolbarPosition) { store.edit { it[positionKey] = value.name } }
    suspend fun setSearchEngine(value: SearchEngine) { store.edit { it[searchKey] = value.name } }
    suspend fun setSearchSuggestions(value: Boolean) { store.edit { it[suggestionsKey] = value } }
    suspend fun setPrivateNotificationPrompted(value: Boolean) { store.edit { it[privateNotificationKey] = value } }
    suspend fun setIntroCompleted(value: Boolean) { store.edit { it[introCompletedKey] = value } }
    suspend fun setBlockThirdPartyCookies(value: Boolean) { store.edit { it[thirdPartyKey] = value } }
    suspend fun setHttpsOnly(value: Boolean) { store.edit { it[httpsOnlyKey] = value } }
    suspend fun setSecureDns(mode: SecureDnsMode, customUrl: String? = null) {
        val validUrl = if (mode == SecureDnsMode.CUSTOM) {
            requireNotNull(customUrl?.let(::validatedSecureDnsUrl))
        } else null
        store.edit { prefs ->
            prefs[dnsModeKey] = mode.name
            if (validUrl != null) prefs[dnsUrlKey] = validUrl
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?): T? = enumValues<T>().firstOrNull { it.name == value }
}
