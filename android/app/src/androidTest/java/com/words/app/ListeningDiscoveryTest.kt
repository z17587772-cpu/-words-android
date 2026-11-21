package com.words.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListeningDiscoveryTest {
    @Test
    fun normalizesEnglishLibriVoxMetadataWithoutGrantingDownloadRights() {
        val raw = JSONObject()
            .put("id", 123)
            .put("title", "An English Story")
            .put("language", "English")
            .put("description", "A sample recording")
            .put("totaltimesecs", 95)
            .put("url_librivox", "https://librivox.org/an-english-story")
            .put("url_zip_file", "https://archive.org/download/an-english-story/an-english-story.zip")
            .put("authors", JSONArray().put(JSONObject().put("first_name", "A").put("last_name", "Reader")))

        val candidate = LibriVoxMetadataParser.normalize(raw)
        assertTrue(candidate != null)
        assertEquals("librivox", candidate!!.provider)
        assertEquals("123", candidate.providerItemId)
        assertEquals(95_000L, candidate.durationMs)
        assertEquals("A Reader", candidate.creator)
        assertEquals("review_required", candidate.rightsStatus)
        assertTrue(candidate.allowedActions.isEmpty())
        assertEquals("application/zip", candidate.mimeType)
    }

    @Test
    fun rejectsNonEnglishAndUntrustedMediaHosts() {
        val base = JSONObject()
            .put("id", 9)
            .put("title", "A recording")
            .put("language", "French")
            .put("url_librivox", "https://librivox.org/a-recording")
        assertNull(LibriVoxMetadataParser.normalize(base))

        val unsafe = JSONObject(base.toString())
            .put("language", "English")
            .put("url_zip_file", "https://example.invalid/audio.zip")
        val candidate = LibriVoxMetadataParser.normalize(unsafe)
        assertTrue(candidate != null)
        assertEquals("", candidate!!.mediaUrl)
        assertEquals("review_required", candidate.rightsStatus)
    }

    @Test
    fun extractsKnownLibriVoxResponseShapes() {
        assertEquals(1, LibriVoxMetadataParser.extractRecords(JSONObject().put("books", JSONArray().put(JSONObject().put("id", 1)))).size)
        assertEquals(1, LibriVoxMetadataParser.extractRecords(JSONObject().put("book", JSONObject().put("id", 2))).size)
    }
}
