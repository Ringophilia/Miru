// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Adds the active player's session to Android's media controls while background playback is enabled. */
class PlayerService : MediaSessionService() {
    internal inner class LocalBinder : Binder() {
        val service: PlayerService get() = this@PlayerService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                pauseAllPlayersAndStopSelf()
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ACTION_LOCAL_BIND) binder else super.onBind(intent)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = null

    internal fun removePlaybackSession(session: MediaSession) {
        if (isSessionAdded(session)) removeSession(session)
        if (sessions.isEmpty()) pauseAllPlayersAndStopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        clearListener()
        super.onDestroy()
    }

    internal companion object {
        const val ACTION_LOCAL_BIND = "app.miru.player.BIND_PLAYBACK"
    }
}
