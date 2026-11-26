// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context
import app.miru.adblock.rust.RustAdblockEngine
import app.miru.chromium.engine.MiruChromiumRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Abstraction over native Chromium runtime adblock calls so unit tests run cleanly on JVM. */
internal interface RuntimeAdblockSink {
    fun setSnapshot(snapshot: ByteArray): Boolean
    fun setRules(rulesText: String)
    fun setEnabled(enabled: Boolean)
    fun setSiteAllowed(host: String, allowed: Boolean)
}

internal object ChromiumRuntimeAdblockSink : RuntimeAdblockSink {
    override fun setSnapshot(snapshot: ByteArray): Boolean = MiruChromiumRuntime.setAdblockSnapshot(snapshot)
    override fun setRules(rulesText: String) = MiruChromiumRuntime.setAdblockRules(rulesText)
    override fun setEnabled(enabled: Boolean) = MiruChromiumRuntime.setAdblockEnabled(enabled)
    override fun setSiteAllowed(host: String, allowed: Boolean) = MiruChromiumRuntime.setSiteAdblockAllowed(host, allowed)
}

/**
 * Manages Miru's bundled & subscription `adblock-rust` filter lists, FlatBuffers snapshot cache
 * (`filesDir/adblock/engine.dat`), per-site allowlist, and user custom rules/picker selectors.
 */
internal class ChromiumAdblockManager(
    private val filesDir: File,
    private val defaultLocaleLanguage: String,
    private val assetReader: (String) -> String,
    private val scope: CoroutineScope,
    private val runtimeSink: RuntimeAdblockSink = ChromiumRuntimeAdblockSink,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        filesDir = context.applicationContext.filesDir,
        defaultLocaleLanguage = Locale.getDefault().language,
        assetReader = { path -> context.applicationContext.assets.open(path).bufferedReader().use { it.readText() } },
        scope = scope,
    )

    private data class CatalogEntry(
        val id: String,
        val title: String,
        val assetPath: String,
        val url: String,
        val chineseOnly: Boolean = false,
    )

    private val catalog = listOf(
        CatalogEntry("miru-core", "EasyList（通用广告拦截）", "adblock/default-core.txt", "https://easylist.to/easylist/easylist.txt"),
        CatalogEntry("miru-privacy", "EasyPrivacy（隐私跟踪拦截）", "adblock/default-privacy.txt", "https://easylist.to/easylist/easyprivacy.txt"),
        CatalogEntry("miru-hosts", "Peter Lowe 广告与追踪列表", "adblock/default-hosts.txt", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0"),
        CatalogEntry("miru-cn", "中文区域规则（EasyList China）", "adblock/default-china.txt", "https://easylist-downloads.adblockplus.org/easylistchina.txt", chineseOnly = true),
    )

    private val adblockDir = File(filesDir, "adblock")
    private val listsDir = File(adblockDir, "lists")
    private val configFile = File(adblockDir, "config.json")
    private val snapshotFile = File(adblockDir, "engine.dat")
    private val hashFile = File(adblockDir, "engine.sha256")
    private val lock = Any()

    private var enabled = true
    private var enabledListIds = linkedSetOf<String>()
    private val allowedHosts = linkedSetOf<String>()
    private val customRules = mutableListOf<String>()
    private val listRuleCounts = linkedMapOf<String, Int>()
    private val listUpdatedAt = linkedMapOf<String, Long>()
    private val totalBlockedCounter = AtomicLong(0L)
    private var initialized = false
    private var customRulesOverride: String? = null

    private val mutableState = MutableStateFlow(NativeAdblockState())
    val state: StateFlow<NativeAdblockState> = mutableState.asStateFlow()

    val isEnabled: Boolean get() = synchronized(lock) { enabled }
    val totalBlocked: Long get() = totalBlockedCounter.get()

    /**
     * Loads or builds the FlatBuffers `adblock-rust` snapshot synchronously on engine startup
     * before any tab begins loading so cold-start tabs never bypass filtering.
     */
    fun initializeSync() {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            adblockDir.mkdirs()
            listsDir.mkdirs()
            loadConfigLocked()
        }
        rebuildAndPushLocked(preferCachedSnapshot = true)
    }

    fun isSiteAllowed(host: String): Boolean {
        val norm = normalizeHost(host) ?: return false
        synchronized(lock) {
            return allowedHosts.any { norm == it || norm.endsWith(".$it") }
        }
    }

    fun setEnabled(next: Boolean) {
        synchronized(lock) {
            enabled = next
            saveConfigLocked()
        }
        runtimeSink.setEnabled(next)
        publishState()
    }

    fun setSiteAllowed(host: String, allowed: Boolean): Boolean {
        val norm = normalizeHost(host) ?: return false
        synchronized(lock) {
            if (allowed) allowedHosts.add(norm) else allowedHosts.remove(norm)
            saveConfigLocked()
        }
        runtimeSink.setSiteAllowed(norm, allowed)
        publishState()
        return true
    }

    fun toggleFilterList(id: String, listEnabled: Boolean) {
        synchronized(lock) {
            if (catalog.none { it.id == id }) return
            if (listEnabled) enabledListIds.add(id) else enabledListIds.remove(id)
            customRulesOverride = null
            saveConfigLocked()
        }
        publishState()
        scope.launch(Dispatchers.IO) { rebuildAndPushLocked(preferCachedSnapshot = false) }
    }

    fun addCustomRule(rule: String): Boolean {
        val trimmed = rule.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('!')) return false
        synchronized(lock) {
            if (trimmed !in customRules) {
                customRules.add(trimmed)
                customRulesOverride = null
                saveConfigLocked()
            }
        }
        publishState()
        rebuildAndPushLocked(preferCachedSnapshot = false)
        return true
    }

    fun removeCustomRule(rule: String): Boolean {
        val trimmed = rule.trim()
        val removed = synchronized(lock) {
            val changed = customRules.remove(trimmed)
            if (changed) {
                customRulesOverride = null
                saveConfigLocked()
            }
            changed
        }
        if (!removed) return false
        publishState()
        scope.launch(Dispatchers.IO) { rebuildAndPushLocked(preferCachedSnapshot = false) }
        return true
    }

    fun setRulesOverrideForTesting(rulesText: String) {
        synchronized(lock) { customRulesOverride = rulesText }
        runtimeSink.setRules(rulesText)
        runtimeSink.setEnabled(isEnabled)
        synchronized(lock) { allowedHosts.toList() }.forEach { runtimeSink.setSiteAllowed(it, true) }
    }

    fun restoreDefaultRulesForTesting() {
        val clearedHosts = synchronized(lock) {
            val prevHosts = allowedHosts.toList()
            customRulesOverride = null
            allowedHosts.clear()
            customRules.clear()
            enabled = true
            saveConfigLocked()
            prevHosts
        }
        clearedHosts.forEach { runtimeSink.setSiteAllowed(it, false) }
        rebuildAndPushLocked(preferCachedSnapshot = true)
    }

    fun setPickerActive(active: Boolean) {
        mutableState.value = mutableState.value.copy(pickerActive = active)
    }

    fun recordBlocked(): Long {
        val next = totalBlockedCounter.incrementAndGet()
        synchronized(lock) {
            if (next % 10L == 0L) saveConfigLocked()
        }
        return next
    }

    fun refreshLists() {
        if (mutableState.value.updatingLists) return
        mutableState.value = mutableState.value.copy(updatingLists = true)
        scope.launch(Dispatchers.IO) {
            try {
                val activeIds = synchronized(lock) { enabledListIds.toSet() }
                val now = System.currentTimeMillis()
                for (entry in catalog) {
                    if (entry.id !in activeIds) continue
                    val downloaded = fetchListText(entry.url) ?: continue
                    val count = countRules(downloaded)
                    if (count <= 0) continue
                    File(listsDir, "${entry.id}.txt").writeText(downloaded, Charsets.UTF_8)
                    synchronized(lock) {
                        listRuleCounts[entry.id] = count
                        listUpdatedAt[entry.id] = now
                    }
                }
                synchronized(lock) {
                    customRulesOverride = null
                    saveConfigLocked()
                }
                rebuildAndPushLocked(preferCachedSnapshot = false)
            } finally {
                mutableState.value = mutableState.value.copy(updatingLists = false)
                publishState()
            }
        }
    }

    private fun rebuildAndPushLocked(preferCachedSnapshot: Boolean) {
        val (rulesBundle, signature) = synchronized(lock) {
            val override = customRulesOverride
            if (override != null) {
                override to sha256Hex(override)
            } else {
                val parts = mutableListOf<String>()
                for (entry in catalog) {
                    if (entry.id !in enabledListIds) continue
                    val text = readListText(entry)
                    listRuleCounts[entry.id] = countRules(text)
                    if (text.isNotBlank()) parts += text
                }
                if (customRules.isNotEmpty()) parts += customRules.joinToString("\n")
                val combined = parts.joinToString("\n")
                combined to sha256Hex(combined)
            }
        }

        val cachedBytes = if (preferCachedSnapshot && snapshotFile.isFile && hashFile.isFile) {
            runCatching {
                val savedHash = hashFile.readText(Charsets.UTF_8).trim()
                if (savedHash == signature) snapshotFile.readBytes() else null
            }.getOrNull()
        } else {
            null
        }

        val snapshotBytes = cachedBytes ?: compileSnapshot(rulesBundle)?.also { compiled ->
            runCatching {
                val tmp = File(adblockDir, ".tmp-engine.dat")
                tmp.writeBytes(compiled)
                if (!tmp.renameTo(snapshotFile)) tmp.copyTo(snapshotFile, overwrite = true)
                hashFile.writeText(signature, Charsets.UTF_8)
            }
        }

        val pushed = if (snapshotBytes != null && snapshotBytes.isNotEmpty()) {
            runtimeSink.setSnapshot(snapshotBytes)
        } else {
            false
        }
        if (!pushed) {
            runtimeSink.setRules(rulesBundle)
        }
        val (curEnabled, hostsCopy) = synchronized(lock) { enabled to allowedHosts.toList() }
        runtimeSink.setEnabled(curEnabled)
        hostsCopy.forEach { runtimeSink.setSiteAllowed(it, true) }
        publishState()
    }

    private fun compileSnapshot(rulesText: String): ByteArray? = runCatching {
        RustAdblockEngine.fromRules(rulesText).use { it.serializeSnapshot() }
    }.getOrNull()

    private fun readListText(entry: CatalogEntry): String {
        val cached = File(listsDir, "${entry.id}.txt")
        if (cached.isFile && cached.length() in 1L..(12L * 1024L * 1024L)) {
            return runCatching { cached.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        }
        return runCatching { assetReader(entry.assetPath) }.getOrDefault("")
    }

    private fun fetchListText(url: String): String? = runCatching {
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val body = resp.body ?: return@runCatching null
            if (body.contentLength() > 12L * 1024L * 1024L) return@runCatching null
            val text = body.string()
            if (text.length > 12 * 1024 * 1024) null else text
        }
    }.getOrNull()

    private fun loadConfigLocked() {
        val defaultLists = catalog
            .filter { !it.chineseOnly || defaultLocaleLanguage.startsWith("zh", ignoreCase = true) }
            .mapTo(linkedSetOf()) { it.id }
        enabledListIds = defaultLists
        allowedHosts.clear()
        customRules.clear()
        listRuleCounts.clear()
        listUpdatedAt.clear()

        if (!configFile.isFile) {
            catalog.forEach { listRuleCounts[it.id] = countRules(readListText(it)) }
            return
        }
        runCatching {
            val json = JSONObject(configFile.readText(Charsets.UTF_8))
            enabled = json.optBoolean("enabled", true)
            totalBlockedCounter.set(json.optLong("totalBlocked", 0L).coerceAtLeast(0L))
            json.optJSONArray("enabledLists")?.let { arr ->
                enabledListIds.clear()
                for (i in 0 until arr.length()) enabledListIds.add(arr.getString(i))
            }
            json.optJSONArray("allowedHosts")?.let { arr ->
                for (i in 0 until arr.length()) normalizeHost(arr.getString(i))?.let(allowedHosts::add)
            }
            json.optJSONArray("customRules")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i).takeIf(String::isNotBlank)?.let(customRules::add)
            }
            json.optJSONObject("updatedAt")?.let { obj ->
                obj.keys().forEach { k -> listUpdatedAt[k] = obj.optLong(k, 0L) }
            }
        }
        catalog.forEach { listRuleCounts[it.id] = countRules(readListText(it)) }
    }

    private fun saveConfigLocked() {
        runCatching {
            val obj = JSONObject()
                .put("enabled", enabled)
                .put("totalBlocked", totalBlockedCounter.get())
                .put("enabledLists", JSONArray(enabledListIds.toList()))
                .put("allowedHosts", JSONArray(allowedHosts.toList()))
                .put("customRules", JSONArray(customRules.toList()))
                .put("updatedAt", JSONObject().apply { listUpdatedAt.forEach { (k, v) -> put(k, v) } })
            val tmp = File(adblockDir, ".tmp-config.json")
            tmp.writeText(obj.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(configFile)) tmp.copyTo(configFile, overwrite = true)
        }
    }

    private fun publishState() {
        val snapshot = synchronized(lock) {
            val lists = catalog.map { entry ->
                AdblockFilterList(
                    id = entry.id,
                    title = entry.title,
                    url = entry.url,
                    enabled = entry.id in enabledListIds,
                    ruleCount = listRuleCounts[entry.id] ?: 0,
                    lastUpdatedEpochMs = listUpdatedAt[entry.id] ?: 0L,
                    builtIn = true,
                )
            }
            NativeAdblockState(
                enabled = enabled,
                engineVersion = "adblock-rust",
                updatingLists = mutableState.value.updatingLists,
                filterLists = lists,
                allowedHosts = allowedHosts.toList(),
                customRules = customRules.toList(),
                pickerActive = mutableState.value.pickerActive,
            )
        }
        mutableState.value = snapshot
    }

    companion object {
        internal fun normalizeHost(raw: String): String? {
            val trimmed = raw.trim().trimEnd('.').lowercase(Locale.ROOT)
            if (trimmed.isEmpty()) return null
            val stripped = if ("://" in trimmed) trimmed.substringAfter("://") else trimmed
            return stripped.substringBefore('/').substringBefore(':').takeIf(String::isNotBlank)
        }

        private fun countRules(text: String): Int =
            text.lineSequence().count { line ->
                val t = line.trim()
                t.isNotEmpty() && !t.startsWith('!') && !t.startsWith('[')
            }

        private fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
