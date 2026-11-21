package com.words.app

import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the player independently from the Activity so listening can continue
 * with the screen locked and expose standard Android media controls.
 */
class ListeningPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?, taskRemoved: Boolean) {
        val activePlayer = player
        if (activePlayer == null || !activePlayer.playWhenReady || activePlayer.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
