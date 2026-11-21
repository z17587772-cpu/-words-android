package com.words.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

/** A raw online recording. It is intentionally separate from reviewed course episodes. */
data class DiscoveredListeningResource(
    val provider: String,
    val providerItemId: String,
    val canonicalUrl: String,
    val mediaUrl: String,
    val title: String,
    val description: String = "",
    val language: String = "",
    val durationMs: Long = 0L,
    val creator: String = "",
    val licenseName: String = "",
    val licenseUrl: String = "",
    val rightsStatus: String = "unknown",
    val allowedActions: Set<String> = emptySet(),
    val attribution: String = "",
    val territory: String = "",
    val mimeType: String = "",
    val expectedSizeBytes: Long = 0L,
    val expectedSha256: String = "",
    val etag: String = "",
    val id: String = "",
    val status: String = "discovered",
    val localFilePath: String = "",
    val pinned: Boolean = false,
    val contentSha256: String = ""
) {
    val stableId: String
        get() {
            val raw = "$provider:$providerItemId"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
}

data class ListeningDownloadCandidate(
    val id: String,
    val mediaUrl: String,
    val title: String,
    val rightsStatus: String,
    val allowedActions: Set<String>,
    val expectedSizeBytes: Long,
    val mimeType: String,
    val status: String
)

data class ListeningResourceFileRecord(
    val id: String,
    val localFilePath: String,
    val expectedSizeBytes: Long,
    val contentSha256: String
)

data class ListeningResourceEvictionCandidate(
    val id: String,
    val localFilePath: String,
    val expectedSizeBytes: Long
)

data class ListeningResourcePlayback(
    val resourceId: String,
    val positionMs: Long,
    val speed: Float
)

data class ListeningProviderState(
    val provider: String,
    val cursorJson: String = "{}",
    val lastSuccessAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val backoffUntil: Long = 0L,
    val etag: String = "",
    val lastError: String = ""
)

/**
 * Local persistence for the on-device discovery lane.
 *
 * This repository never promotes raw recordings into formal lessons and never
 * grants download permission based only on a provider name or hosting URL.
 */
class ListeningDiscoveryRepository(private val helper: WordsDatabase) {
    private val db: SQLiteDatabase get() = helper.writableDatabase

    fun upsertCandidate(resource: DiscoveredListeningResource, nowMs: Long = System.currentTimeMillis()): String {
        require(resource.provider.matches(Regex("[a-z0-9][a-z0-9._-]{1,39}")))
        require(resource.providerItemId.isNotBlank() && resource.providerItemId.length <= 200)
        require(resource.title.isNotBlank() && resource.title.length <= 500)
        require(resource.durationMs >= 0L && resource.expectedSizeBytes >= 0L)
        require(resource.rightsStatus in setOf("unknown", "review_required", "cleared", "rejected", "withdrawn"))
        require(resource.expectedSha256.isBlank() || resource.expectedSha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(resource.canonicalUrl.isBlank() || isHttpsUrl(resource.canonicalUrl))
        require(resource.mediaUrl.isBlank() || isHttpsUrl(resource.mediaUrl))
        val id = resource.stableId
        val values = ContentValues().apply {
            put("id", id)
            put("provider", resource.provider)
            put("provider_item_id", resource.providerItemId)
            put("canonical_url", resource.canonicalUrl)
            put("media_url", resource.mediaUrl)
            put("title", resource.title)
            put("description", resource.description)
            put("language", resource.language)
            put("duration_ms", resource.durationMs)
            put("creator", resource.creator)
            put("license_name", resource.licenseName)
            put("license_url", resource.licenseUrl)
            put("rights_status", resource.rightsStatus)
            put("allowed_actions", resource.allowedActions.sorted().joinToString(","))
            put("attribution", resource.attribution)
            put("territory", resource.territory)
            put("mime_type", resource.mimeType)
            put("expected_size_bytes", resource.expectedSizeBytes)
            put("expected_sha256", resource.expectedSha256.lowercase())
            put("discovered_at", nowMs)
            put("last_checked_at", nowMs)
            put("etag", resource.etag)
        }
        val database = db
        database.beginTransaction()
        try {
            val inserted = database.insertWithOnConflict(
                "listening_discovered_resources", null, values, SQLiteDatabase.CONFLICT_IGNORE
            )
            if (inserted == -1L) {
                // Refresh provider metadata while preserving favorites and local state;
                // a source rights change below deliberately revokes stale access.
                val update = ContentValues(values).apply {
                    remove("id")
                    remove("provider")
                    remove("provider_item_id")
                    remove("discovered_at")
                }
                val existingRights = database.rawQuery(
                    "SELECT rights_status FROM listening_discovered_resources WHERE id=?",
                    arrayOf(id)
                ).use { if (it.moveToFirst()) it.getString(0) else "unknown" }
                val newActions = resource.allowedActions
                if (existingRights == "cleared" &&
                    (resource.rightsStatus != "cleared" || !newActions.containsAll(setOf("in_app_playback", "offline_download")))
                ) {
                    // A source license withdrawal/reclassification immediately
                    // removes the playable local pointer and prevents future use.
                    update.put("status", "expired")
                    update.put("local_file_path", "")
                    update.put("content_sha256", "")
                }
                database.update("listening_discovered_resources", update, "id=?", arrayOf(id))
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return id
    }

    fun revokeProviderItem(provider: String, providerItemPrefix: String): Int =
        db.update(
            "listening_discovered_resources",
            ContentValues().apply {
                put("rights_status", "review_required")
                put("allowed_actions", "")
                put("status", "expired")
                put("local_file_path", "")
                put("content_sha256", "")
            },
            "provider=? AND provider_item_id GLOB ?",
            arrayOf(provider, providerItemPrefix + "*")
        )

    fun queueDownload(resourceId: String): Boolean {
        val candidate = db.rawQuery(
            "SELECT rights_status,allowed_actions,status FROM listening_discovered_resources WHERE id=?",
            arrayOf(resourceId)
        ).use { c ->
            if (!c.moveToFirst()) return false
            Triple(c.getString(0), c.getString(1).split(',').filter { it.isNotBlank() }.toSet(), c.getString(2))
        }
        if (candidate.first != "cleared" ||
            !candidate.second.contains("in_app_playback") ||
            !candidate.second.contains("offline_download") ||
            candidate.third !in setOf("discovered", "eligible", "failed")
        ) return false
        return db.update(
            "listening_discovered_resources",
            ContentValues().apply { put("status", "queued") },
            "id=? AND status IN ('discovered','eligible','failed')",
            arrayOf(resourceId)
        ) == 1
    }

    fun updateDownloadStatus(resourceId: String, status: String) {
        require(status in setOf("queued", "downloading", "failed", "expired", "rejected"))
        db.update(
            "listening_discovered_resources",
            ContentValues().apply {
                put("status", status)
            },
            "id=?",
            arrayOf(resourceId)
        )
    }

    fun saveLocalFile(resourceId: String, absolutePath: String, sha256: String, durationMs: Long = 0L, nowMs: Long = System.currentTimeMillis()) {
        require(absolutePath.isNotBlank() && sha256.matches(Regex("[a-fA-F0-9]{64}")))
        val changed = db.update(
            "listening_discovered_resources",
            ContentValues().apply {
                put("local_file_path", absolutePath)
                put("content_sha256", sha256.lowercase())
                put("status", "downloaded")
                put("downloaded_at", nowMs)
                if (durationMs > 0L) put("duration_ms", durationMs)
            },
            "id=? AND rights_status='cleared' AND status IN ('queued','downloading')",
            arrayOf(resourceId)
        )
        check(changed == 1) { "Resource is not eligible for local publication" }
    }

    fun getDownloadCandidate(resourceId: String): ListeningDownloadCandidate? =
        helper.readableDatabase.rawQuery(
            "SELECT id,media_url,title,rights_status,allowed_actions,expected_size_bytes,mime_type,status FROM listening_discovered_resources WHERE id=?",
            arrayOf(resourceId)
        ).use { c ->
            if (!c.moveToFirst()) null else ListeningDownloadCandidate(
                id = c.getString(0),
                mediaUrl = c.getString(1),
                title = c.getString(2),
                rightsStatus = c.getString(3),
                allowedActions = c.getString(4).split(',').filter { it.isNotBlank() }.toSet(),
                expectedSizeBytes = c.getLong(5),
                mimeType = c.getString(6),
                status = c.getString(7)
            )
        }

    fun listResources(limit: Int = 100, downloadedOnly: Boolean = false): List<DiscoveredListeningResource> {
        val safeLimit = limit.coerceIn(1, 500)
        val where = if (downloadedOnly) "WHERE status='downloaded'" else ""
        return helper.readableDatabase.rawQuery(
            "SELECT id,provider,provider_item_id,canonical_url,media_url,title,description,language,duration_ms,creator,license_name,license_url,rights_status,allowed_actions,attribution,territory,mime_type,expected_size_bytes,expected_sha256,content_sha256,etag,status,local_file_path,pinned FROM listening_discovered_resources $where ORDER BY pinned DESC,COALESCE(last_played_at,0) DESC,discovered_at DESC LIMIT ?",
            arrayOf(safeLimit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    DiscoveredListeningResource(
                        id = c.getString(0), provider = c.getString(1), providerItemId = c.getString(2),
                        canonicalUrl = c.getString(3), mediaUrl = c.getString(4),
                        title = c.getString(5), description = c.getString(6), language = c.getString(7),
                        durationMs = c.getLong(8), creator = c.getString(9), licenseName = c.getString(10),
                        licenseUrl = c.getString(11), rightsStatus = c.getString(12),
                        allowedActions = c.getString(13).split(',').filter { it.isNotBlank() }.toSet(),
                        attribution = c.getString(14), territory = c.getString(15), mimeType = c.getString(16),
                        expectedSizeBytes = c.getLong(17), expectedSha256 = c.getString(18), contentSha256 = c.getString(19), etag = c.getString(20),
                        status = c.getString(21), localFilePath = c.getString(22), pinned = c.getInt(23) == 1
                    )
                )
            }
        }
    }

    fun listDownloadedLocalFiles(limit: Int = 10_000): List<ListeningResourceFileRecord> {
        val safeLimit = limit.coerceIn(1, 10_000)
        return helper.readableDatabase.rawQuery(
            "SELECT id,local_file_path,expected_size_bytes,content_sha256 FROM listening_discovered_resources WHERE status='downloaded' AND local_file_path<>'' ORDER BY downloaded_at DESC LIMIT ?",
            arrayOf(safeLimit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(ListeningResourceFileRecord(c.getString(0), c.getString(1), c.getLong(2), c.getString(3)))
            }
        }
    }

    fun markMissingLocalFile(resourceId: String, path: String): Boolean =
        db.update(
            "listening_discovered_resources",
            ContentValues().apply {
                put("local_file_path", "")
                put("content_sha256", "")
                put("status", "eligible")
            },
            "id=? AND local_file_path=? AND status='downloaded'",
            arrayOf(resourceId, path)
        ) == 1

    fun downloadedStorageBytes(): Long =
        helper.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(file_size),0) FROM (SELECT MAX(expected_size_bytes) AS file_size FROM listening_discovered_resources WHERE status='downloaded' AND local_file_path<>'' GROUP BY local_file_path)",
            null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    fun findDownloadedFileByHash(sha256: String): String? =
        helper.readableDatabase.rawQuery(
            "SELECT local_file_path FROM listening_discovered_resources WHERE content_sha256=? AND status='downloaded' AND local_file_path<>'' LIMIT 1",
            arrayOf(sha256.lowercase())
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun isLocalPathShared(resourceId: String, path: String): Boolean {
        if (path.isBlank()) return false
        return helper.readableDatabase.rawQuery(
            "SELECT 1 FROM listening_discovered_resources WHERE local_file_path=? AND status='downloaded' AND id<>? LIMIT 1",
            arrayOf(path, resourceId)
        ).use { it.moveToFirst() }
    }

    fun getEvictionCandidates(
        limit: Int = 200,
        recentlyPlayedProtectionMs: Long = 30L * 24L * 60L * 60L * 1000L,
        nowMs: Long = System.currentTimeMillis()
    ): List<ListeningResourceEvictionCandidate> {
        val safeLimit = limit.coerceIn(1, 500)
        val playedBefore = nowMs - recentlyPlayedProtectionMs
        return helper.readableDatabase.rawQuery(
            "SELECT id,local_file_path,expected_size_bytes FROM listening_discovered_resources WHERE status='downloaded' AND pinned=0 AND (last_played_at IS NULL OR last_played_at<?) ORDER BY COALESCE(last_played_at,0) ASC,downloaded_at ASC LIMIT ?",
            arrayOf(playedBefore.toString(), safeLimit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    ListeningResourceEvictionCandidate(c.getString(0), c.getString(1), c.getLong(2))
                )
            }
        }
    }

    fun markLocalFileEvicted(
        resourceId: String,
        path: String,
        nowMs: Long = System.currentTimeMillis(),
        recentlyPlayedProtectionMs: Long = 30L * 24L * 60L * 60L * 1000L
    ): Boolean {
        val playedBefore = nowMs - recentlyPlayedProtectionMs
        return db.update(
            "listening_discovered_resources",
            ContentValues().apply {
                put("local_file_path", "")
                put("content_sha256", "")
                put("status", "expired")
            },
            "id=? AND local_file_path=? AND status='downloaded' AND pinned=0 AND (last_played_at IS NULL OR last_played_at<?)",
            arrayOf(resourceId, path, playedBefore.toString())
        ) == 1
    }

    fun requeueExpiredDownload(resourceId: String): Boolean {
        val allowed = helper.readableDatabase.rawQuery(
            "SELECT rights_status,allowed_actions,status FROM listening_discovered_resources WHERE id=?",
            arrayOf(resourceId)
        ).use { c ->
            if (!c.moveToFirst()) return false
            c.getString(0) == "cleared" &&
                c.getString(1).split(',').containsAll(listOf("in_app_playback", "offline_download")) &&
                c.getString(2) == "expired"
        }
        if (!allowed) return false
        return db.update(
            "listening_discovered_resources",
            ContentValues().apply { put("status", "queued") },
            "id=? AND status='expired' AND rights_status='cleared'",
            arrayOf(resourceId)
        ) == 1
    }

    fun setPinned(resourceId: String, pinned: Boolean) {
        db.update(
            "listening_discovered_resources",
            ContentValues().apply { put("pinned", if (pinned) 1 else 0) },
            "id=?",
            arrayOf(resourceId)
        )
    }

    /** Removes only the local audio pointer; metadata, favorite state and playback progress remain. */
    fun removeLocalCopy(resourceId: String, path: String): Boolean {
        if (path.isBlank()) return false
        return db.update(
            "listening_discovered_resources",
            ContentValues().apply {
                put("local_file_path", "")
                put("content_sha256", "")
                put("status", "expired")
            },
            "id=? AND local_file_path=? AND status='downloaded'",
            arrayOf(resourceId, path)
        ) == 1
    }

    fun saveResourcePlayback(resourceId: String, positionMs: Long, speed: Float) {
        require(positionMs >= 0L && speed.isFinite())
        val values = ContentValues().apply {
            put("resource_id", resourceId)
            put("position_ms", positionMs)
            put("speed", speed.coerceIn(0.5f, 2f))
            put("updated_at", System.currentTimeMillis())
        }
        val inserted = db.insertWithOnConflict(
            "listening_discovered_playback", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        if (inserted == -1L) db.update(
            "listening_discovered_playback", values, "resource_id=?", arrayOf(resourceId)
        )
        db.update(
            "listening_discovered_resources",
            ContentValues().apply { put("last_played_at", System.currentTimeMillis()) },
            "id=?",
            arrayOf(resourceId)
        )
    }

    fun getResourcePlayback(resourceId: String): ListeningResourcePlayback? =
        helper.readableDatabase.rawQuery(
            "SELECT resource_id,position_ms,speed FROM listening_discovered_playback WHERE resource_id=?",
            arrayOf(resourceId)
        ).use { c ->
            if (!c.moveToFirst()) null else ListeningResourcePlayback(c.getString(0), c.getLong(1), c.getFloat(2))
        }

    fun saveProviderState(state: ListeningProviderState) {
        require(state.provider.matches(Regex("[a-z0-9][a-z0-9._-]{1,39}")))
        val values = ContentValues().apply {
            put("provider", state.provider)
            put("cursor_json", state.cursorJson)
            put("last_success_at", state.lastSuccessAt)
            put("last_attempt_at", state.lastAttemptAt)
            put("backoff_until", state.backoffUntil)
            put("etag", state.etag)
            put("last_error", state.lastError.take(1000))
        }
        val inserted = db.insertWithOnConflict(
            "listening_provider_state", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        if (inserted == -1L) db.update(
            "listening_provider_state", values, "provider=?", arrayOf(state.provider)
        )
    }

    fun getProviderState(provider: String): ListeningProviderState? =
        helper.readableDatabase.rawQuery(
            "SELECT provider,cursor_json,last_success_at,last_attempt_at,backoff_until,etag,last_error FROM listening_provider_state WHERE provider=?",
            arrayOf(provider)
        ).use { c ->
            if (!c.moveToFirst()) null else ListeningProviderState(
                c.getString(0), c.getString(1), c.getLong(2), c.getLong(3),
                c.getLong(4), c.getString(5), c.getString(6)
            )
        }

    private fun isHttpsUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}
