// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context

/**
 * Synchronous, thread-safe per-origin permission store isolated by normal vs off-the-record
 * contexts. Normal grants persist via [SharedPreferences]; private grants remain in memory only
 * and are wiped whenever all private tabs close.
 */
internal class ChromiumSitePermissionStore(
    private val readPersisted: (String) -> String?,
    private val writePersisted: (String, String?) -> Unit,
    private val clearPersisted: () -> Unit,
    private val readAllPersisted: () -> Map<String, String>,
) {
    constructor(context: Context) : this(
        prefs(context),
    )

    private constructor(backing: PrefsBacking) : this(
        readPersisted = backing.read,
        writePersisted = backing.write,
        clearPersisted = backing.clear,
        readAllPersisted = backing.readAll,
    )

    private class PrefsBacking(
        val read: (String) -> String?,
        val write: (String, String?) -> Unit,
        val clear: () -> Unit,
        val readAll: () -> Map<String, String>,
    )

    private val lock = Any()
    private val privateChoices = mutableMapOf<String, MutableMap<SitePermissionKind, SitePermissionChoice>>()

    fun read(origin: String, isPrivate: Boolean): Map<SitePermissionKind, SitePermissionChoice> = synchronized(lock) {
        val normalized = siteOrigin(origin) ?: return SitePermissionKind.entries.associateWith { SitePermissionChoice.ASK }
        val saved = if (isPrivate) {
            privateChoices[normalized].orEmpty()
        } else {
            buildMap {
                for (kind in SitePermissionKind.entries) {
                    val raw = readPersisted(key(normalized, kind)) ?: continue
                    val choice = runCatching { SitePermissionChoice.valueOf(raw) }.getOrNull() ?: continue
                    if (choice != SitePermissionChoice.ASK) put(kind, choice)
                }
            }
        }
        SitePermissionKind.entries.associateWith { saved[it] ?: SitePermissionChoice.ASK }
    }

    fun readOne(origin: String, isPrivate: Boolean, kind: SitePermissionKind): SitePermissionChoice = synchronized(lock) {
        val normalized = siteOrigin(origin) ?: return SitePermissionChoice.ASK
        if (isPrivate) {
            privateChoices[normalized]?.get(kind) ?: SitePermissionChoice.ASK
        } else {
            val raw = readPersisted(key(normalized, kind)) ?: return SitePermissionChoice.ASK
            runCatching { SitePermissionChoice.valueOf(raw) }.getOrNull() ?: SitePermissionChoice.ASK
        }
    }

    fun put(origin: String, isPrivate: Boolean, kind: SitePermissionKind, choice: SitePermissionChoice): Unit = synchronized(lock) {
        val normalized = siteOrigin(origin) ?: return@synchronized
        if (isPrivate) {
            if (choice == SitePermissionChoice.ASK) {
                privateChoices[normalized]?.let { map ->
                    map.remove(kind)
                    if (map.isEmpty()) privateChoices.remove(normalized)
                }
            } else {
                privateChoices.getOrPut(normalized) { mutableMapOf() }[kind] = choice
            }
        } else {
            val storageKey = key(normalized, kind)
            if (choice == SitePermissionChoice.ASK) {
                writePersisted(storageKey, null)
            } else {
                writePersisted(storageKey, choice.name)
            }
        }
    }

    fun reset(origin: String, isPrivate: Boolean): Unit = synchronized(lock) {
        val normalized = siteOrigin(origin) ?: return@synchronized
        if (isPrivate) {
            privateChoices.remove(normalized)
        } else {
            for (kind in SitePermissionKind.entries) {
                writePersisted(key(normalized, kind), null)
            }
        }
    }

    fun clearPrivate() = synchronized(lock) {
        privateChoices.clear()
    }

    fun clearAll() = synchronized(lock) {
        clearPersisted()
        privateChoices.clear()
    }

    fun listSavedDefaultOrigins(): Map<String, Map<SitePermissionKind, SitePermissionChoice>> = synchronized(lock) {
        val result = linkedMapOf<String, MutableMap<SitePermissionKind, SitePermissionChoice>>()
        readAllPersisted().forEach { (rawKey, rawValue) ->
            val sep = rawKey.lastIndexOf('|')
            if (sep <= 0) return@forEach
            val origin = siteOrigin(rawKey.substring(0, sep)) ?: return@forEach
            val kind = runCatching { SitePermissionKind.valueOf(rawKey.substring(sep + 1)) }.getOrNull() ?: return@forEach
            val choice = runCatching { SitePermissionChoice.valueOf(rawValue) }.getOrNull()
                ?.takeIf { it != SitePermissionChoice.ASK } ?: return@forEach
            result.getOrPut(origin) { linkedMapOf() }[kind] = choice
        }
        result
    }

    private fun key(origin: String, kind: SitePermissionKind): String = "$origin|${kind.name}"

    companion object {
        private fun prefs(context: Context): PrefsBacking {
            val sp = context.applicationContext.getSharedPreferences("miru_chromium_site_permissions", Context.MODE_PRIVATE)
            return PrefsBacking(
                read = { sp.getString(it, null) },
                write = { k, v ->
                    sp.edit().apply {
                        if (v == null) remove(k) else putString(k, v)
                    }.apply()
                },
                clear = { sp.edit().clear().apply() },
                readAll = { sp.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap() },
            )
        }
    }
}
