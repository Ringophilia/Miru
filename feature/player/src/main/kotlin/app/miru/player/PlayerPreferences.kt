// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.playerPreferences by preferencesDataStore("miru_player_settings")

data class PlayerPreferences(
    val cacheMb: Int = 1024,
    val bufferSeconds: Int = 60,
    val doubleTapSeconds: Int = 10,
    val holdSpeed: Float = 2f,
    val dragRangeSeconds: Int = 120,
    val centerDoubleTap: Boolean = true,
    val brightnessGesture: Boolean = true,
    val volumeGesture: Boolean = true,
    val haptics: Boolean = true,
    val autoPip: Boolean = true,
    val webHoldSpeed: Boolean = true,
    val autoTakeover: Boolean = false,
    val subtitleSizeSp: Float = 20f,
    val subtitleBottomMargin: Float = 0.08f,
    val subtitleBackgroundOpacity: Float = 0.6f,
) {
    companion object {
        fun dataSaverActive(metered: Boolean, status: Int): Boolean = metered && status in setOf(
            android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED,
        )
    }
    fun bounded() = copy(cacheMb = cacheMb.coerceIn(128, 4096), bufferSeconds = bufferSeconds.coerceIn(15, 180), doubleTapSeconds = doubleTapSeconds.coerceIn(5, 60),
        holdSpeed = holdSpeed.takeIf { it.isFinite() }?.coerceIn(0.5f, 3f) ?: 2f, dragRangeSeconds = dragRangeSeconds.coerceIn(30, 600),
        subtitleSizeSp = subtitleSizeSp.takeIf { it.isFinite() }?.coerceIn(12f, 40f) ?: 20f,
        subtitleBottomMargin = subtitleBottomMargin.takeIf { it.isFinite() }?.coerceIn(0f, 0.3f) ?: 0.08f,
        subtitleBackgroundOpacity = subtitleBackgroundOpacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.6f)
    fun bufferMillis(dataSaver: Boolean): Pair<Int, Int> {
        val maximum = (if(dataSaver) minOf(bufferSeconds, 30) else bufferSeconds).coerceIn(15, 180) * 1000
        return maxOf(15_000, maximum / 2) to maximum
    }
}

class PlayerPreferencesRepository(context: Context) {
    private val store = context.applicationContext.playerPreferences
    private val cache = intPreferencesKey("cache.mb")
    private val buffer = intPreferencesKey("buffer.seconds")
    private val seek = intPreferencesKey("gesture.doubleTapSeconds")
    private val hold = floatPreferencesKey("gesture.holdSpeed")
    private val drag = intPreferencesKey("gesture.dragSeconds")
    private val center = booleanPreferencesKey("gesture.centerDoubleTap")
    private val brightness = booleanPreferencesKey("gesture.brightness")
    private val volume = booleanPreferencesKey("gesture.volume")
    private val haptics = booleanPreferencesKey("gesture.haptics")
    private val pip = booleanPreferencesKey("player.autoPip")
    private val webHold = booleanPreferencesKey("media.webLongPressSpeed")
    private val takeover = booleanPreferencesKey("media.autoTakeover")
    private val subtitleSize = floatPreferencesKey("subtitle.sizeSp")
    private val subtitleMargin = floatPreferencesKey("subtitle.bottomMargin")
    private val subtitleBackground = floatPreferencesKey("subtitle.backgroundOpacity")
    private fun decode(p: Preferences) = PlayerPreferences(p[cache] ?: 1024, p[buffer] ?: 60, p[seek] ?: 10, p[hold] ?: 2f, p[drag] ?: 120,
        p[center] ?: true, p[brightness] ?: true, p[volume] ?: true, p[haptics] ?: true, p[pip] ?: true, p[webHold] ?: true, p[takeover] ?: false,
        p[subtitleSize] ?: 20f, p[subtitleMargin] ?: 0.08f, p[subtitleBackground] ?: 0.6f).bounded()
    val settings = store.data.catch { if(it is IOException) emit(emptyPreferences()) else throw it }.map(::decode)
    suspend fun update(transform: (PlayerPreferences) -> PlayerPreferences) {
        store.edit { p ->
            val next = transform(decode(p)).bounded()
            p[cache] = next.cacheMb; p[buffer] = next.bufferSeconds; p[seek] = next.doubleTapSeconds; p[hold] = next.holdSpeed; p[drag] = next.dragRangeSeconds
            p[center] = next.centerDoubleTap; p[brightness] = next.brightnessGesture; p[volume] = next.volumeGesture; p[haptics] = next.haptics; p[pip] = next.autoPip
            p[webHold] = next.webHoldSpeed; p[takeover] = next.autoTakeover
            p[subtitleSize] = next.subtitleSizeSp; p[subtitleMargin] = next.subtitleBottomMargin; p[subtitleBackground] = next.subtitleBackgroundOpacity
        }
    }
}
