package com.words.app

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import android.util.Log
import java.util.concurrent.Executors

class ListeningPlaybackService : MediaSessionService() {
    private val checkpointHandler = Handler(Looper.getMainLooper())
    private val persistenceExecutor = Executors.newSingleThreadExecutor()
    private var database: WordsDatabase? = null
    private var repository: ListeningRepository? = null
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private val playbackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) scheduleCheckpoint() else {
                checkpointHandler.removeCallbacks(checkpointTask)
                checkpointNow()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) checkpointNow()
        }
    }

    private val checkpointTask = object : Runnable {
        override fun run() {
            checkpointNow()
            if (player?.isPlaying == true) {
                checkpointHandler.postDelayed(this, CHECKPOINT_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer.addListener(playbackListener)
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun scheduleCheckpoint() {
        checkpointHandler.removeCallbacks(checkpointTask)
        checkpointHandler.postDelayed(checkpointTask, CHECKPOINT_INTERVAL_MS)
    }

    private fun checkpointNow() {
        val activePlayer = player ?: return
        val item = activePlayer.currentMediaItem ?: return
        val mediaId = item.mediaId.takeIf { it.isNotBlank() } ?: return
        val position = activePlayer.currentPosition.coerceAtLeast(0L)
        val speed = activePlayer.playbackParameters.speed
        val extras = item.mediaMetadata.extras
        val resourceId = extras?.getString("resource_id")
        if (mediaId.startsWith("resource:") && !resourceId.isNullOrBlank()) {
            persistenceExecutor.execute {
                try {
                    val helper = database ?: WordsDatabase(this).also { database = it }
                    ListeningDiscoveryRepository(helper).saveResourcePlayback(resourceId, position, speed)
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to persist discovered-resource playback checkpoint", error)
                }
            }
            return
        }
        val audioUri = item.localConfiguration?.uri?.toString() ?: return
        val itemRevision = extras?.getString("audio_revision") ?: return
        persistenceExecutor.execute {
            try {
                val store = repository ?: run {
                    val helper = database ?: WordsDatabase(this).also { database = it }
                    ListeningRepository(helper).also { repository = it }
                }
                if (!store.isKnownPlaybackRevision(mediaId, audioUri, itemRevision, position)) return@execute
                store.savePlayback(mediaId, position, speed, itemRevision)
            } catch (error: Exception) {
                Log.w(TAG, "Unable to persist listening playback checkpoint", error)
            }
        }
    }

    override fun onDestroy() {
        checkpointHandler.removeCallbacks(checkpointTask)
        checkpointNow()
        player?.removeListener(playbackListener)
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        persistenceExecutor.execute {
            database?.close()
            database = null
            repository = null
        }
        persistenceExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ListeningPlayback"
        private const val CHECKPOINT_INTERVAL_MS = 5_000L
    }
}
