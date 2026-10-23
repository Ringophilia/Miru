// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.UUID

class PlaybackRequest(
    val url: String,
    val title: String,
    val pageUrl: String = "",
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val positionMs: Long = 0,
    val fromWeb: Boolean = false,
    val isPrivate: Boolean = false,
    val refreshHeaders: (suspend () -> Map<String, String>?)? = null,
    val onReturn: ((failed: Boolean) -> Unit)? = null,
    val onVideoSize: ((width: Int, height: Int) -> Unit)? = null,
    val offlineId: String? = null,
)

/** Intents contain an opaque, one-use token, never cookies, signed URLs or private titles. */
object PlaybackRequests {
    private val requests = linkedMapOf<String, Pair<Long, PlaybackRequest>>()
    @Synchronized fun intent(context: Context, request: PlaybackRequest): Intent {
        requests.entries.removeAll { SystemClock.elapsedRealtime() - it.value.first > 60_000 }
        while(requests.size >= 8) requests.remove(requests.keys.first())
        val token = UUID.randomUUID().toString()
        requests[token] = SystemClock.elapsedRealtime() to request
        return Intent(context, PlayerActivity::class.java).putExtra("miru.playback.token", token)
    }
    @Synchronized internal fun take(token: String): PlaybackRequest? = requests.remove(token)?.takeIf { SystemClock.elapsedRealtime() - it.first <= 60_000 }?.second
}
