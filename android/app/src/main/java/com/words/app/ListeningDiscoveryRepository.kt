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
    val etag: String = ""
) {
    val stableId: String
        get() {
            val raw = "$provider:$providerItemId"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
}

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
                // Refresh provider metadata without overwriting user pinning,
                // local files, download state, or a prior rights decision.
                val update = ContentValues(values).apply {
                    remove("id")
                    remove("provider")
                    remove("provider_item_id")
                    remove("rights_status")
                    remove("allowed_actions")
                    remove("discovered_at")
                }
                val existingRights = database.rawQuery(
                    "SELECT rights_status FROM listening_discovered_resources WHERE id=?",
                    arrayOf(id)
                ).use { if (it.moveToFirst()) it.getString(0) else "unknown" }
                if (existingRights == "unknown" || existingRights == "review_required") {
                    update.put("rights_status", resource.rightsStatus)
                    update.put("allowed_actions", resource.allowedActions.sorted().joinToString(","))
                }
                database.update("listening_discovered_resources", update, "id=?", arrayOf(id))
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return id
    }

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
        require(status in setOf("queued", "downloading", "downloaded", "failed", "expired", "rejected"))
        db.update(
            "listening_discovered_resources",
            ContentValues().apply {
                put("status", status)
                if (status == "downloaded") put("downloaded_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(resourceId)
        )
    }

    fun saveLocalFile(resourceId: String, absolutePath: String, sha256: String, nowMs: Long = System.currentTimeMillis()) {
        require(absolutePath.isNotBlank() && sha256.matches(Regex("[a-fA-F0-9]{64}")))
        val changed = db.update(
            "listening_discovered_resources",
            ContentValues().apply {
                put("local_file_path", absolutePath)
                put("content_sha256", sha256.lowercase())
                put("status", "downloaded")
                put("downloaded_at", nowMs)
            },
            "id=? AND rights_status='cleared' AND status IN ('queued','downloading')",
            arrayOf(resourceId)
        )
        check(changed == 1) { "Resource is not eligible for local publication" }
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

    fun listResources(limit: Int = 100, downloadedOnly: Boolean = false): List<DiscoveredListeningResource> {
        val safeLimit = limit.coerceIn(1, 500)
        val where = if (downloadedOnly) "WHERE status='downloaded'" else ""
        return helper.readableDatabase.rawQuery(
            "SELECT provider,provider_item_id,canonical_url,media_url,title,description,language,duration_ms,creator,license_name,license_url,rights_status,allowed_actions,attribution,territory,mime_type,expected_size_bytes,expected_sha256,etag FROM listening_discovered_resources $where ORDER BY discovered_at DESC LIMIT ?",
            arrayOf(safeLimit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    DiscoveredListeningResource(
                        provider = c.getString(0), providerItemId = c.getString(1),
                        canonicalUrl = c.getString(2), mediaUrl = c.getString(3),
                        title = c.getString(4), description = c.getString(5),
                        language = c.getString(6), durationMs = c.getLong(7),
                        creator = c.getString(8), licenseName = c.getString(9),
                        licenseUrl = c.getString(10), rightsStatus = c.getString(11),
                        allowedActions = c.getString(12).split(',').filter { it.isNotBlank() }.toSet(),
                        attribution = c.getString(13), territory = c.getString(14),
                        mimeType = c.getString(15), expectedSizeBytes = c.getLong(16),
                        expectedSha256 = c.getString(17), etag = c.getString(18)
                    )
                )
            }
        }
    }

    private fun isHttpsUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}
