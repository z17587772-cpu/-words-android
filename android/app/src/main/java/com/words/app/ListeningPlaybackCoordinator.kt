package com.words.app

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController

/**
 * Owns playback resume/checkpoint policy so Activity rendering and navigation
 * do not also own persistence details.
 */
class ListeningPlaybackCoordinator(
    private val repository: ListeningRepository,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    private var episodeId: String? = null
    private var mediaUri: String? = null
    private var durationMs: Long = 0L
    private var checkpointRunnable: Runnable? = null

    fun play(controller: MediaController, episode: ListeningEpisode, uri: String) {
        episodeId = episode.id
        mediaUri = uri
        durationMs = episode.durationMs

        val currentUri = controller.currentMediaItem?.localConfiguration?.uri?.toString()
        if (currentUri != uri) {
            val saved = repository.getPlayback(episode.id)
            val savedPosition = saved?.positionMs ?: 0L
            val restorePosition = if (episode.durationMs > 0L &&
                savedPosition >= episode.durationMs - COMPLETION_GUARD_MS
            ) 0L else savedPosition.coerceAtLeast(0L)

            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(episode.title)
                        .setArtist("Words · 听力训练")
                        .build()
                )
                .build()
            controller.setMediaItem(mediaItem, restorePosition)
            controller.prepare()
            controller.setPlaybackSpeed(saved?.speed ?: DEFAULT_SPEED)
        }

        controller.play()
        scheduleCheckpoint(controller)
    }

    fun checkpoint(controller: MediaController) {
        val id = episodeId ?: return
        val expectedUri = mediaUri ?: return
        val actualUri = controller.currentMediaItem?.localConfiguration?.uri?.toString()
        if (actualUri != expectedUri) return

        val position = controller.currentPosition.coerceAtLeast(0L)
        if (durationMs > 0L && position >= durationMs) return
        repository.savePlayback(id, position, controller.playbackParameters.speed)
    }

    fun release(controller: MediaController?) {
        if (controller != null) checkpoint(controller)
        checkpointRunnable?.let(handler::removeCallbacks)
        checkpointRunnable = null
    }

    private fun scheduleCheckpoint(controller: MediaController) {
        checkpointRunnable?.let(handler::removeCallbacks)
        val task = object : Runnable {
            override fun run() {
                if (controller.currentMediaItem?.localConfiguration?.uri?.toString() != mediaUri) {
                    checkpointRunnable = null
                    return
                }
                checkpoint(controller)
                if (controller.isPlaying) handler.postDelayed(this, CHECKPOINT_INTERVAL_MS)
                else checkpointRunnable = null
            }
        }
        checkpointRunnable = task
        handler.postDelayed(task, CHECKPOINT_INTERVAL_MS)
    }

    companion object {
        private const val CHECKPOINT_INTERVAL_MS = 5_000L
        private const val COMPLETION_GUARD_MS = 2_000L
        private const val DEFAULT_SPEED = 1.0f
    }
}
