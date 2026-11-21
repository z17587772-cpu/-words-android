package com.words.app

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController

/**
 * Owns playback resume/checkpoint policy so Activity rendering and navigation
 * do not also own persistence details.
 */
class ListeningPlaybackCoordinator(
    private val repository: ListeningRepository
) {
    private var episodeId: String? = null
    private var mediaUri: String? = null
    private var durationMs: Long = 0L

    fun play(controller: MediaController, episode: ListeningEpisode, uri: String) {
        val currentItem = controller.currentMediaItem
        val currentUri = currentItem?.localConfiguration?.uri?.toString()
        val sameEpisode = currentItem != null && currentUri == uri && currentItem.mediaId == episode.id
        if (!sameEpisode) checkpoint(controller)

        episodeId = episode.id
        mediaUri = uri
        durationMs = episode.durationMs

        if (!sameEpisode) {
            val saved = repository.getPlayback(episode.id)
            val savedPosition = saved?.positionMs ?: 0L
            val restorePosition = if (episode.durationMs > 0L &&
                savedPosition >= episode.durationMs - COMPLETION_GUARD_MS
            ) 0L else savedPosition.coerceAtLeast(0L)

            val mediaItem = MediaItem.Builder()
                .setMediaId(episode.id)
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
    }

    fun currentSpeed(controller: MediaController?, episode: ListeningEpisode): Float {
        val expectedUri = episode.audioUrl
        val currentUri = controller?.currentMediaItem?.localConfiguration?.uri?.toString()
        return if (controller != null && !expectedUri.isNullOrBlank() && currentUri == expectedUri) {
            controller.playbackParameters.speed
        } else {
            repository.getPlayback(episode.id)?.speed ?: DEFAULT_SPEED
        }
    }

    fun setSpeed(controller: MediaController?, episode: ListeningEpisode, speed: Float) {
        val safeSpeed = speed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: DEFAULT_SPEED
        val expectedUri = episode.audioUrl
        val currentUri = controller?.currentMediaItem?.localConfiguration?.uri?.toString()
        if (controller != null && !expectedUri.isNullOrBlank() && currentUri == expectedUri) {
            controller.setPlaybackSpeed(safeSpeed)
            checkpoint(controller)
        } else {
            val saved = repository.getPlayback(episode.id)
            repository.savePlayback(episode.id, saved?.positionMs ?: 0L, safeSpeed)
        }
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
    }

    companion object {
        private const val COMPLETION_GUARD_MS = 2_000L
        private const val DEFAULT_SPEED = 1.0f
    }
}
