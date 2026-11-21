package com.words.app

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListeningDiscoveryRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanDatabase() {
        context.deleteDatabase("words_local.db")
    }

    @Test
    fun rediscoveryIsIdempotentAndPreservesLocalStateAndRightsDecision() {
        context.deleteDatabase("words_local.db")
        val helper = WordsDatabase(context)
        val repository = ListeningDiscoveryRepository(helper)
        val initial = DiscoveredListeningResource(
            provider = "librivox",
            providerItemId = "12345",
            canonicalUrl = "https://librivox.org/example",
            mediaUrl = "https://archive.org/download/example/audio.mp3",
            title = "Example recording",
            language = "English",
            durationMs = 120_000,
            rightsStatus = "review_required"
        )

        val id = repository.upsertCandidate(initial, 100L)
        assertEquals(id, repository.upsertCandidate(initial.copy(title = "Updated title"), 200L))
        assertEquals(1, helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM listening_discovered_resources WHERE provider='librivox' AND provider_item_id='12345'",
            null
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 })

        helper.writableDatabase.update(
            "listening_discovered_resources",
            ContentValues().apply {
                put("rights_status", "cleared")
                put("allowed_actions", "in_app_playback,offline_download")
                put("pinned", 1)
                put("status", "downloaded")
                put("local_file_path", "/private/example.mp3")
                put("content_sha256", "a".repeat(64))
            },
            "id=?",
            arrayOf(id)
        )
        repository.upsertCandidate(initial.copy(
            title = "Provider title refresh",
            rightsStatus = "cleared",
            allowedActions = setOf("in_app_playback", "offline_download")
        ), 300L)

        val row = helper.readableDatabase.rawQuery(
            "SELECT title,rights_status,status,pinned,local_file_path FROM listening_discovered_resources WHERE id=?",
            arrayOf(id)
        ).use { c ->
            assertTrue(c.moveToFirst())
            listOf(c.getString(0), c.getString(1), c.getString(2), c.getInt(3).toString(), c.getString(4))
        }
        assertEquals("Provider title refresh", row[0])
        assertEquals("cleared", row[1])
        assertEquals("downloaded", row[2])
        assertEquals("1", row[3])
        assertEquals("/private/example.mp3", row[4])
        repository.saveProviderState(ListeningProviderState("librivox", cursorJson = """{"since":123}""", lastSuccessAt = 300L))
        assertNotNull(repository.getProviderState("librivox"))
        repository.saveResourcePlayback(id, 12_345L, 1.25f)
        val playback = repository.getResourcePlayback(id)
        assertNotNull(playback)
        assertEquals(12_345L, playback!!.positionMs)
        assertEquals(1.25f, playback.speed, 0.001f)
        val listed = repository.listResources(10).single()
        assertEquals(id, listed.id)
        assertEquals("downloaded", listed.status)
        assertEquals("/private/example.mp3", listed.localFilePath)
        assertEquals("a".repeat(64), listed.contentSha256)
        assertTrue(listed.pinned)
        val duplicate = DiscoveredListeningResource(
            provider = "internet_archive",
            providerItemId = "same-audio#chapter.mp3",
            canonicalUrl = "https://archive.org/details/same-audio",
            mediaUrl = "https://archive.org/download/same-audio/chapter.mp3",
            title = "Duplicate recording",
            rightsStatus = "cleared",
            allowedActions = setOf("in_app_playback", "offline_download"),
            expectedSizeBytes = 100L
        )
        val duplicateId = repository.upsertCandidate(duplicate)
        assertTrue(repository.queueDownload(duplicateId))
        repository.updateDownloadStatus(duplicateId, "downloading")
        repository.saveLocalFile(duplicateId, "/private/example.mp3", "a".repeat(64), 120_000L)
        assertEquals("/private/example.mp3", repository.findDownloadedFileByHash("a".repeat(64)))
        assertTrue(repository.isLocalPathShared(id, "/private/example.mp3"))
        assertEquals(100L, repository.downloadedStorageBytes())
        assertEquals("a".repeat(64), repository.listDownloadedLocalFiles().first { it.id == duplicateId }.contentSha256)
        assertFalse(repository.markLocalFileEvicted(id, "/private/example.mp3"))
        repository.setPinned(id, false)
        helper.writableDatabase.update(
            "listening_discovered_resources",
            ContentValues().apply { put("last_played_at", System.currentTimeMillis()) },
            "id=?",
            arrayOf(id)
        )
        assertFalse(repository.markLocalFileEvicted(id, "/private/example.mp3"))
        helper.writableDatabase.update(
            "listening_discovered_resources",
            ContentValues().apply {
                put("last_played_at", System.currentTimeMillis() - 31L * 24L * 60L * 60L * 1000L)
            },
            "id=?",
            arrayOf(id)
        )
        // Recently-played protection expires after 30 days; old history must
        // remain eligible for eviction instead of being permanently protected.
        assertTrue(repository.markLocalFileEvicted(id, "/private/example.mp3"))
        assertTrue(repository.requeueExpiredDownload(id))
        helper.close()
    }

    @Test
    fun sourceLicenseRevocationRemovesLocalPlaybackAndDownloadAccess() {
        context.deleteDatabase("words_local.db")
        val helper = WordsDatabase(context)
        val repository = ListeningDiscoveryRepository(helper)
        val candidate = DiscoveredListeningResource(
            provider = "internet_archive",
            providerItemId = "license-item#track.mp3",
            canonicalUrl = "https://archive.org/details/license-item",
            mediaUrl = "https://archive.org/download/license-item/track.mp3",
            title = "Licensed track",
            rightsStatus = "cleared",
            allowedActions = setOf("in_app_playback", "offline_download"),
            expectedSizeBytes = 100L
        )
        val id = repository.upsertCandidate(candidate)
        assertTrue(repository.queueDownload(id))
        repository.updateDownloadStatus(id, "downloading")
        repository.saveLocalFile(id, "/private/licensed.mp3", "b".repeat(64), 60_000L)

        repository.upsertCandidate(candidate.copy(
            rightsStatus = "review_required",
            allowedActions = emptySet(),
            licenseName = "",
            licenseUrl = ""
        ))

        val row = helper.readableDatabase.rawQuery(
            "SELECT rights_status,allowed_actions,status,local_file_path FROM listening_discovered_resources WHERE id=?",
            arrayOf(id)
        ).use { c ->
            assertTrue(c.moveToFirst())
            listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3))
        }
        assertEquals("review_required", row[0])
        assertEquals("", row[1])
        assertEquals("expired", row[2])
        assertEquals("", row[3])
        // A download worker finishing after revocation must not resurrect the row.
        repository.updateDownloadStatus(id, "failed")
        assertEquals("expired", helper.readableDatabase.rawQuery(
            "SELECT status FROM listening_discovered_resources WHERE id=?",
            arrayOf(id)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else "" })
        assertFalse(repository.queueDownload(id))
        helper.close()
    }

    @Test
    fun removingLocalCopyPreservesFavoriteAndPlaybackHistoryAndSharedBytes() {
        context.deleteDatabase("words_local.db")
        val helper = WordsDatabase(context)
        val repository = ListeningDiscoveryRepository(helper)
        val resource = DiscoveredListeningResource(
            provider = "internet_archive",
            providerItemId = "manual-delete#track.mp3",
            canonicalUrl = "https://archive.org/details/manual-delete",
            mediaUrl = "https://archive.org/download/manual-delete/track.mp3",
            title = "Keep my history",
            rightsStatus = "cleared",
            allowedActions = setOf("in_app_playback", "offline_download"),
            expectedSizeBytes = 100L
        )
        val id = repository.upsertCandidate(resource)
        assertTrue(repository.queueDownload(id))
        repository.updateDownloadStatus(id, "downloading")
        repository.saveLocalFile(id, "/private/shared.mp3", "c".repeat(64), 90_000L)
        repository.setPinned(id, true)
        repository.saveResourcePlayback(id, 42_000L, 1.25f)

        val duplicate = repository.upsertCandidate(resource.copy(providerItemId = "other-source#track.mp3"))
        assertTrue(repository.queueDownload(duplicate))
        repository.updateDownloadStatus(duplicate, "downloading")
        repository.saveLocalFile(duplicate, "/private/shared.mp3", "c".repeat(64), 90_000L)

        assertTrue(repository.removeLocalCopy(id, "/private/shared.mp3"))
        assertTrue(repository.isLocalPathShared(id, "/private/shared.mp3"))
        val row = repository.listResources(10).first { it.id == id }
        assertEquals("expired", row.status)
        assertTrue(row.pinned)
        assertEquals("", row.localFilePath)
        val playback = repository.getResourcePlayback(id)
        assertNotNull(playback)
        assertEquals(42_000L, playback!!.positionMs)
        assertEquals(1.25f, playback.speed, 0.001f)
        helper.close()
    }

    @Test
    fun downloadQueueFailsClosedUnlessOfflineRightsAreExplicitlyCleared() {
        context.deleteDatabase("words_local.db")
        val helper = WordsDatabase(context)
        val repository = ListeningDiscoveryRepository(helper)
        val resource = DiscoveredListeningResource(
            provider = "librivox",
            providerItemId = "987",
            canonicalUrl = "https://librivox.org/sample",
            mediaUrl = "https://archive.org/download/sample/audio.mp3",
            title = "Sample",
            language = "English",
            rightsStatus = "review_required"
        )
        val id = repository.upsertCandidate(resource)
        assertFalse(repository.queueDownload(id))

        helper.writableDatabase.update(
            "listening_discovered_resources",
            ContentValues().apply {
                put("rights_status", "cleared")
                put("allowed_actions", "in_app_playback")
            },
            "id=?",
            arrayOf(id)
        )
        assertFalse(repository.queueDownload(id))

        helper.writableDatabase.update(
            "listening_discovered_resources",
            ContentValues().apply { put("allowed_actions", "in_app_playback,offline_download") },
            "id=?",
            arrayOf(id)
        )
        assertTrue(repository.queueDownload(id))
        repository.updateDownloadStatus(id, "downloading")
        repository.saveLocalFile(id, "/private/sample.mp3", "a".repeat(64))
        assertEquals("downloaded", helper.readableDatabase.rawQuery(
            "SELECT status FROM listening_discovered_resources WHERE id=?",
            arrayOf(id)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else "" })
        assertEquals(1, repository.listDownloadedLocalFiles().size)
        assertTrue(repository.markMissingLocalFile(id, "/private/sample.mp3"))
        assertTrue(repository.queueDownload(id))
        assertTrue(repository.listDownloadedLocalFiles().isEmpty())
        helper.close()
    }
    @Test
    fun abandonedDownloadIsRecoveredAndCanBeQueuedAgain() {
        context.deleteDatabase("words_local.db")
        val helper = WordsDatabase(context)
        val repository = ListeningDiscoveryRepository(helper)
        val candidate = DiscoveredListeningResource(
            provider = "internet_archive",
            providerItemId = "stale-download",
            canonicalUrl = "https://archive.org/details/stale-download",
            mediaUrl = "https://archive.org/download/stale-download/audio.mp3",
            title = "Stale download",
            rightsStatus = "cleared",
            allowedActions = setOf("in_app_playback", "offline_download"),
            expectedSizeBytes = 100L,
            mimeType = "audio/mpeg"
        )
        val id = repository.upsertCandidate(candidate)
        assertTrue(repository.queueDownload(id))
        repository.updateDownloadStatus(id, "downloading")
        assertTrue(repository.recoverStaleDownloads(System.currentTimeMillis() + 1L).contains(id))
        assertTrue(repository.queueDownload(id))
        assertEquals("queued", repository.listResources(10).first { it.id == id }.status)
        helper.close()
    }

}
