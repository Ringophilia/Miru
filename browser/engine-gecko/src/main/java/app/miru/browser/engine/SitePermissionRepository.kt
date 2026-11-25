// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.browser.engine.gecko.await
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
import org.mozilla.geckoview.StorageController

/** Calls are serialized by the coordinator's mutex, shared with browsing-data deletion. */
internal class SitePermissionRepository(
    context: Context,
    private val runtime: () -> GeckoRuntime?,
) {
    private val database by lazy { PermissionDatabase(context.applicationContext) }
    private val privateChoices = mutableMapOf<SiteKey, MutableMap<SitePermissionKind, SitePermissionChoice>>()
    private var privateGeneration = 0L

    /**
     * Miru owns geolocation and autoplay decisions. The pinned geolocation patch
     * prevents new native grants from being cached. Remove legacy overrides and
     * cached autoplay answers before sessions load, so current policy is consulted.
     */
    suspend fun prepareNativePolicy() {
        // The first storage query can wait on Gecko's cold profile startup.
        val overrides = withNativeStorage(STARTUP_NATIVE_QUERY_TIMEOUT_MS) { storage ->
            allNativePolicyPermissions(storage).filter { it.value != VALUE_PROMPT }
        }
        if (overrides.isEmpty()) return

        withNativeStorage { storage ->
            overrides.forEach { storage.setPermission(it, VALUE_PROMPT) }
            repeat(VERIFY_ATTEMPTS) { attempt ->
                if (allNativePolicyPermissions(storage).all { it.value == VALUE_PROMPT }) return@withNativeStorage
                if (attempt < VERIFY_ATTEMPTS - 1) delay(VERIFY_DELAY_MS)
            }
            error("SITE_PERMISSION_NATIVE_MIGRATION_FAILED")
        }
    }

    suspend fun read(site: PermissionSite): Map<SitePermissionKind, SitePermissionChoice> {
        requireSite(site)
        val saved = if (site.isPrivate) {
            privateChoices[SiteKey(site.origin, site.contextId)].orEmpty().toMap()
        } else {
            withContext(Dispatchers.IO) {
                buildMap {
                    database.readableDatabase.query(
                        TABLE, arrayOf("kind", "choice"), "origin = ? AND context_key = ?",
                        arrayOf(site.origin, contextKey(site.contextId)), null, null, null,
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            put(SitePermissionKind.valueOf(cursor.getString(0)), SitePermissionChoice.valueOf(cursor.getString(1)))
                        }
                    }
                }
            }
        }
        return SitePermissionKind.entries.associateWith { kind ->
            saved[kind] ?: when (kind) {
                SitePermissionKind.NOTIFICATIONS -> nativeNotificationChoice(site)
                SitePermissionKind.PROTECTED_MEDIA -> nativeMediaKeyChoice(site)
                else -> SitePermissionChoice.ASK
            }
        }
    }

    suspend fun put(site: PermissionSite, kind: SitePermissionKind, choice: SitePermissionChoice) =
        put(site, kind, choice, synchronizeNotification = true)

    /** Site consent is separate from Android authorization. Do not grant Gecko before Android answers. */
    suspend fun rememberNotificationConsent(site: PermissionSite) =
        put(site, SitePermissionKind.NOTIFICATIONS, SitePermissionChoice.ALLOW, synchronizeNotification = false)

    private suspend fun put(site: PermissionSite, kind: SitePermissionKind, choice: SitePermissionChoice, synchronizeNotification: Boolean) {
        requireSite(site)
        val generation = privateGeneration
        // Do not commit a local choice when an existing engine decision could not be changed.
        if (kind == SitePermissionKind.LOCATION) setNativeLocation(site, SitePermissionChoice.ASK)
        if (kind == SitePermissionKind.NOTIFICATIONS && synchronizeNotification) setNativeNotification(site, choice)
        if (kind == SitePermissionKind.PROTECTED_MEDIA) setNativeMediaKey(site, choice)
        if (site.isPrivate) {
            // An IPC await may outlive the last private tab. Never restore a retired
            // private session's decision into a later private browsing session.
            check(generation == privateGeneration) { "SITE_PERMISSION_PRIVATE_RETIRED" }
            val key = SiteKey(site.origin, site.contextId)
            if (choice == SitePermissionChoice.ASK) {
                privateChoices[key]?.let { choices ->
                    choices.remove(kind)
                    if (choices.isEmpty()) privateChoices.remove(key)
                }
            } else {
                privateChoices.getOrPut(key) { mutableMapOf() }[kind] = choice
            }
            return
        }
        withContext(Dispatchers.IO) {
            val db = database.writableDatabase
            if (choice == SitePermissionChoice.ASK) {
                db.delete(TABLE, "origin = ? AND context_key = ? AND kind = ?", arrayOf(site.origin, contextKey(site.contextId), kind.name))
            } else {
                val values = ContentValues().apply {
                    put("origin", site.origin)
                    put("context_key", contextKey(site.contextId))
                    put("kind", kind.name)
                    put("choice", choice.name)
                }
                check(db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
                    "SITE_PERMISSION_WRITE_FAILED"
                }
            }
        }
    }

    suspend fun listSavedDefaultOrigins(): Map<String, Map<SitePermissionKind, SitePermissionChoice>> = withContext(Dispatchers.IO) {
        val result = linkedMapOf<String, MutableMap<SitePermissionKind, SitePermissionChoice>>()
        database.readableDatabase.query(
            TABLE, arrayOf("origin", "kind", "choice"), "context_key = ?", arrayOf("0"),
            null, null, "origin ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val origin = siteOrigin(cursor.getString(0)) ?: continue
                val kind = runCatching { SitePermissionKind.valueOf(cursor.getString(1)) }.getOrNull() ?: continue
                val choice = runCatching { SitePermissionChoice.valueOf(cursor.getString(2)) }.getOrNull() ?: continue
                result.getOrPut(origin) { linkedMapOf() }[kind] = choice
            }
        }
        result
    }

    suspend fun reset(site: PermissionSite) {
        requireSite(site)
        val generation = privateGeneration
        setNativeLocation(site, SitePermissionChoice.ASK)
        setNativeNotification(site, SitePermissionChoice.ASK)
        setNativeMediaKey(site, SitePermissionChoice.ASK)
        resetNativeAutoplay(site)
        if (site.isPrivate) {
            check(generation == privateGeneration) { "SITE_PERMISSION_PRIVATE_RETIRED" }
            privateChoices.remove(SiteKey(site.origin, site.contextId))
        } else {
            withContext(Dispatchers.IO) {
                database.writableDatabase.delete(TABLE, "origin = ? AND context_key = ?", arrayOf(site.origin, contextKey(site.contextId)))
            }
        }
    }

    /** The coordinator also awaits Gecko's site-settings clear under the same mutex. */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) { database.writableDatabase.delete(TABLE, null, null) }
        clearPrivate()
    }

    fun clearPrivate() {
        privateGeneration++
        privateChoices.clear()
    }

    /** Null means Gecko has no current geolocation override for this exact browsing scope. */
    suspend fun nativeLocationChoice(site: PermissionSite): SitePermissionChoice? {
        requireSite(site)
        return withNativeStorage { storage ->
            val choices = locationPermissions(storage, site).map { it.value.toChoice() }.distinct()
            check(choices.all { it == SitePermissionChoice.ASK }) { "SITE_PERMISSION_NATIVE_OVERRIDE" }
            choices.singleOrNull()
        }
    }

    /** Updates only real, scoped objects returned by Gecko; never clears a host's other settings. */
    suspend fun setNativeLocation(site: PermissionSite, choice: SitePermissionChoice) {
        requireSite(site)
        // Recreating a native ALLOW/DENY would bypass the document-owned policy
        // after navigation. Persistent choices belong only to Miru's own store.
        require(choice == SitePermissionChoice.ASK) { "SITE_PERMISSION_NATIVE_MUST_PROMPT" }
        withNativeStorage { storage ->
            val existing = locationPermissions(storage, site)
            if (existing.isEmpty()) return@withNativeStorage
            val value = choice.toNativeValue()
            existing.forEach { storage.setPermission(it, value) }
            // setPermission is fire-and-forget. Confirm its effect before allowing local state
            // or UI success to advance; resetting to PROMPT may remove the native object.
            repeat(VERIFY_ATTEMPTS) { attempt ->
                val current = locationPermissions(storage, site)
                if ((current.isNotEmpty() || choice == SitePermissionChoice.ASK) && current.all { it.value == value }) {
                    return@withNativeStorage
                }
                if (attempt < VERIFY_ATTEMPTS - 1) delay(VERIFY_DELAY_MS)
            }
            error("SITE_PERMISSION_NATIVE_NOT_VERIFIED")
        }
    }

    // Unlike geolocation, Gecko must retain notification grants so Notification.permission
    // and service-worker showNotification can work without a new document prompt.
    private suspend fun nativeNotificationChoice(site: PermissionSite): SitePermissionChoice =
        withNativeStorage { storage ->
            notificationPermissions(storage, site).firstOrNull()?.value?.toChoice() ?: SitePermissionChoice.ASK
        }

    private suspend fun setNativeNotification(site: PermissionSite, choice: SitePermissionChoice) {
        withNativeStorage { storage ->
            val existing = notificationPermissions(storage, site)
            if (existing.isEmpty()) return@withNativeStorage
            val value = choice.toNativeValue()
            existing.forEach { storage.setPermission(it, value) }
            repeat(VERIFY_ATTEMPTS) { attempt ->
                val current = notificationPermissions(storage, site)
                if (current.all { it.value == value } && (current.isNotEmpty() || choice == SitePermissionChoice.ASK)) {
                    return@withNativeStorage
                }
                if (attempt < VERIFY_ATTEMPTS - 1) delay(VERIFY_DELAY_MS)
            }
            error("SITE_PERMISSION_NATIVE_NOT_VERIFIED")
        }
    }

    /** Restore only an existing native denial after remembered site consent and a real system grant. */
    suspend fun restoreNotificationAfterSystemGrant(site: PermissionSite, stillCurrent: () -> Boolean) {
        requireSite(site)
        withNativeStorage { storage ->
            val denied = notificationPermissions(storage, site).filter { it.value == VALUE_DENY }
            if (denied.isEmpty() || !stillCurrent()) return@withNativeStorage
            denied.forEach { storage.setPermission(it, VALUE_ALLOW) }
            repeat(VERIFY_ATTEMPTS) { attempt ->
                val current = notificationPermissions(storage, site)
                if (current.isNotEmpty() && current.all { it.value == VALUE_ALLOW }) return@withNativeStorage
                if (attempt < VERIFY_ATTEMPTS - 1) delay(VERIFY_DELAY_MS)
            }
            error("SITE_PERMISSION_NATIVE_NOT_VERIFIED")
        }
    }

    private suspend fun notificationPermissions(storage: StorageController, site: PermissionSite): List<ContentPermission> =
        checkNotNull(storage.getPermissions(site.origin, site.contextId, site.isPrivate).await()) {
            "SITE_PERMISSION_NATIVE_QUERY_FAILED"
        }.filter {
            it.permission == PERMISSION_DESKTOP_NOTIFICATION && it.privateMode == site.isPrivate &&
                it.contextId == site.contextId && siteOrigin(it.uri) == site.origin
        }

    private suspend fun nativeMediaKeyChoice(site: PermissionSite): SitePermissionChoice = withNativeStorage { storage ->
        mediaKeyPermissions(storage, site).firstOrNull()?.value?.toChoice() ?: SitePermissionChoice.ASK
    }

    private suspend fun setNativeMediaKey(site: PermissionSite, choice: SitePermissionChoice) = withNativeStorage { storage ->
        val existing = mediaKeyPermissions(storage, site)
        if (existing.isEmpty()) return@withNativeStorage
        val value = choice.toNativeValue()
        existing.forEach { storage.setPermission(it, value) }
        repeat(VERIFY_ATTEMPTS) { attempt ->
            val current = mediaKeyPermissions(storage, site)
            if (current.all { it.value == value } && (current.isNotEmpty() || choice == SitePermissionChoice.ASK)) return@withNativeStorage
            if (attempt < VERIFY_ATTEMPTS - 1) delay(VERIFY_DELAY_MS)
        }
        error("SITE_PERMISSION_NATIVE_NOT_VERIFIED")
    }

    private suspend fun mediaKeyPermissions(storage: StorageController, site: PermissionSite): List<ContentPermission> =
        checkNotNull(storage.getPermissions(site.origin, site.contextId, site.isPrivate).await()) {
            "SITE_PERMISSION_NATIVE_QUERY_FAILED"
        }.filter {
            it.permission == PERMISSION_MEDIA_KEY_SYSTEM_ACCESS && it.privateMode == site.isPrivate &&
                it.contextId == site.contextId && siteOrigin(it.uri) == site.origin
        }

    /** A new document must ask the coordinator again after its autoplay choice changes. */
    suspend fun resetNativeAutoplay(site: PermissionSite) {
        requireSite(site)
        withNativeStorage { storage ->
            suspend fun current() = checkNotNull(storage.getPermissions(site.origin, site.contextId, site.isPrivate).await()) {
                "SITE_PERMISSION_NATIVE_QUERY_FAILED"
            }.filter {
                it.permission in setOf(PERMISSION_AUTOPLAY_AUDIBLE, PERMISSION_AUTOPLAY_INAUDIBLE) &&
                    it.privateMode == site.isPrivate && it.contextId == site.contextId && siteOrigin(it.uri) == site.origin
            }
            current().forEach { storage.setPermission(it, VALUE_PROMPT) }
            repeat(VERIFY_ATTEMPTS) { attempt ->
                if (current().all { it.value == VALUE_PROMPT }) return@withNativeStorage
                if (attempt < VERIFY_ATTEMPTS - 1) delay(VERIFY_DELAY_MS)
            }
            error("SITE_PERMISSION_NATIVE_NOT_VERIFIED")
        }
    }

    private suspend fun locationPermissions(storage: StorageController, site: PermissionSite): List<ContentPermission> =
        checkNotNull(storage.getPermissions(site.origin, site.contextId, site.isPrivate).await()) {
            "SITE_PERMISSION_NATIVE_QUERY_FAILED"
        }.filter {
            it.permission == PERMISSION_GEOLOCATION && it.privateMode == site.isPrivate &&
                it.contextId == site.contextId && siteOrigin(it.uri) == site.origin
        }

    private suspend fun allNativePolicyPermissions(storage: StorageController): List<ContentPermission> =
        checkNotNull(storage.getAllPermissions().await()) { "SITE_PERMISSION_NATIVE_QUERY_FAILED" }
            .filter { it.permission in setOf(PERMISSION_GEOLOCATION, PERMISSION_AUTOPLAY_AUDIBLE, PERMISSION_AUTOPLAY_INAUDIBLE) }

    private suspend fun <T> withNativeStorage(
        timeoutMillis: Long = NATIVE_TIMEOUT_MS,
        block: suspend (StorageController) -> T,
    ): T = try {
        withContext(Dispatchers.Main.immediate) {
            val storage = runtime()?.storageController ?: error("SITE_PERMISSION_NATIVE_UNAVAILABLE")
            val completed = withTimeoutOrNull(timeoutMillis) { NativeValue(block(storage)) }
                ?: error("SITE_PERMISSION_NATIVE_TIMEOUT")
            completed.value
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Gecko errors can include the URI. Expose only a fixed code, without a raw cause.
        throw IllegalStateException("SITE_PERMISSION_NATIVE_FAILED")
    }

    private fun requireSite(site: PermissionSite) {
        require(siteOrigin(site.origin) == site.origin) { "SITE_PERMISSION_INVALID_ORIGIN" }
    }

    // Avoid a generated toString that could expose a private origin or context.
    private class SiteKey(private val origin: String, private val contextId: String?) {
        override fun equals(other: Any?) = other is SiteKey && origin == other.origin && contextId == other.contextId
        override fun hashCode() = 31 * origin.hashCode() + (contextId?.hashCode() ?: 0)
    }

    private class NativeValue<T>(val value: T)

    private class PermissionDatabase(context: Context) : SQLiteOpenHelper(context, "miru_site_permissions.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (origin TEXT NOT NULL, context_key TEXT NOT NULL, " +
                    "kind TEXT NOT NULL CHECK(kind IN ('CAMERA','MICROPHONE','LOCATION','NOTIFICATIONS','PROTECTED_MEDIA')), " +
                    "choice TEXT NOT NULL CHECK(choice IN ('ALLOW','BLOCK')), " +
                    "PRIMARY KEY(origin, context_key, kind))",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE $TABLE RENAME TO old_site_permissions")
                onCreate(db)
                db.execSQL("INSERT INTO $TABLE SELECT * FROM old_site_permissions")
                db.execSQL("DROP TABLE old_site_permissions")
            }
        }
    }

    private companion object {
        const val TABLE = "site_permissions"
        const val NATIVE_TIMEOUT_MS = 5_000L
        const val STARTUP_NATIVE_QUERY_TIMEOUT_MS = 15_000L
        const val VERIFY_ATTEMPTS = 8
        const val VERIFY_DELAY_MS = 75L

        // Prefix the real value so a null sentinel cannot collide with any context ID.
        fun contextKey(contextId: String?) = if (contextId == null) "0" else "1$contextId"

        fun SitePermissionChoice.toNativeValue() = when (this) {
            SitePermissionChoice.ALLOW -> VALUE_ALLOW
            SitePermissionChoice.BLOCK -> VALUE_DENY
            SitePermissionChoice.ASK -> VALUE_PROMPT
        }

        fun Int.toChoice() = when (this) {
            VALUE_ALLOW -> SitePermissionChoice.ALLOW
            VALUE_DENY -> SitePermissionChoice.BLOCK
            VALUE_PROMPT -> SitePermissionChoice.ASK
            else -> error("SITE_PERMISSION_NATIVE_VALUE_INVALID")
        }
    }
}
