// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.miru.common.SearchEngine
import app.miru.common.AccentColor
import app.miru.common.validateCustomSearchTemplate
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.emptyPreferences

private val Context.miruPreferences by preferencesDataStore(name = "miru_settings")
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ToolbarPosition { TOP, BOTTOM }

val defaultClearOnExitTypes: Set<BrowsingDataType> = setOf(
    BrowsingDataType.COOKIES,
    BrowsingDataType.WEB_CACHE,
    BrowsingDataType.HISTORY,
)

data class BrowserSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    // Off by default so the brand palette (matching the launcher icon) is what people see first.
    val dynamicColor: Boolean = false,
    val accent: AccentColor = AccentColor.RINGO_RED,
    val toolbarPosition: ToolbarPosition = ToolbarPosition.TOP,
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val customSearchUrl: String? = null,
    val searchSuggestions: Boolean = true,
    val webZoomPercent: Int = 100,
    val clearDataOnExit: Boolean = false,
    val clearOnExitTypes: Set<BrowsingDataType> = defaultClearOnExitTypes,
    val ntpShowShortcuts: Boolean = true,
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
    private val suggestionsKey = booleanPreferencesKey("search.suggestions")
    private val privateNotificationKey = booleanPreferencesKey("privacy.privateNotificationPrompted")
    private val thirdPartyKey = booleanPreferencesKey("privacy.blockThirdPartyCookies")
    private val httpsOnlyKey = booleanPreferencesKey("privacy.httpsOnly")
    private val dnsModeKey = stringPreferencesKey("privacy.secureDns.mode")
    private val dnsUrlKey = stringPreferencesKey("privacy.secureDns.customUrl")
    val settings: Flow<BrowserSettings> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map(::decodeBrowserSettings)

    internal fun decodeBrowserSettings(values: Preferences): BrowserSettings {
        val dnsUrl = values[dnsUrlKey]?.let(::validatedSecureDnsUrl)
        val dnsMode = enumValue<SecureDnsMode>(values[dnsModeKey])
            ?.takeIf { it != SecureDnsMode.CUSTOM || dnsUrl != null } ?: SecureDnsMode.AUTOMATIC
        val customSearch = values[customSearchKey]?.trim()?.takeIf(::validateCustomSearchTemplate)
        val rawEngine = enumValue<SearchEngine>(values[searchKey]) ?: SearchEngine.GOOGLE
        val exitTypes = decodeClearTypes(values[clearOnExitTypesKey])
        return BrowserSettings(
            theme = enumValue<ThemeMode>(values[themeKey]) ?: ThemeMode.SYSTEM,
            dynamicColor = values[dynamicKey] ?: false,
            accent = AccentColor.fromPreference(values[accentKey]),
            toolbarPosition = enumValue<ToolbarPosition>(values[positionKey]) ?: ToolbarPosition.TOP,
            searchEngine = if (rawEngine == SearchEngine.CUSTOM && customSearch == null) SearchEngine.GOOGLE else rawEngine,
            customSearchUrl = customSearch,
            searchSuggestions = values[suggestionsKey] ?: true,
            webZoomPercent = (values[webZoomKey] ?: 100).coerceIn(50, 200),
            clearDataOnExit = values[clearOnExitKey] ?: false,
            clearOnExitTypes = exitTypes,
            ntpShowShortcuts = values[ntpShortcutsKey] ?: true,
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
    suspend fun setCustomSearchUrl(template: String) {
        val trimmed = template.trim()
        require(validateCustomSearchTemplate(trimmed)) { "INVALID_CUSTOM_SEARCH_TEMPLATE" }
        store.edit { prefs ->
            prefs[customSearchKey] = trimmed
            prefs[searchKey] = SearchEngine.CUSTOM.name
        }
    }
    suspend fun setSearchSuggestions(value: Boolean) { store.edit { it[suggestionsKey] = value } }
    suspend fun setWebZoomPercent(percent: Int) { store.edit { it[webZoomKey] = percent.coerceIn(50, 200) } }
    suspend fun setClearDataOnExit(enabled: Boolean) { store.edit { it[clearOnExitKey] = enabled } }
    suspend fun setClearOnExitTypes(types: Set<BrowsingDataType>) {
        store.edit { prefs ->
            prefs[clearOnExitTypesKey] = encodeClearTypes(types)
        }
    }
    suspend fun setNtpShowShortcuts(show: Boolean) { store.edit { it[ntpShortcutsKey] = show } }
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

    companion object {
        internal val searchKey = stringPreferencesKey("search.engine")
        internal val customSearchKey = stringPreferencesKey("search.customUrl")
        internal val webZoomKey = intPreferencesKey("appearance.webZoomPercent")
        internal val clearOnExitKey = booleanPreferencesKey("privacy.clearOnExit")
        internal val clearOnExitTypesKey = stringPreferencesKey("privacy.clearOnExitTypes")
        internal val ntpShortcutsKey = booleanPreferencesKey("appearance.ntpShowShortcuts")

        internal fun decodeClearTypes(raw: String?): Set<BrowsingDataType> {
            if (raw == null) return defaultClearOnExitTypes
            if (raw.isBlank()) return emptySet()
            return raw.split(',').mapNotNull { name -> BrowsingDataType.entries.firstOrNull { it.name == name.trim() } }.toSet()
        }

        internal fun encodeClearTypes(types: Set<BrowsingDataType>): String = types.joinToString(",") { it.name }
    }
}
