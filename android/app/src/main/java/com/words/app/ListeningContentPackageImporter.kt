package com.words.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

sealed class ListeningPackageImportResult {
    data class Installed(
        val packageId: String,
        val packageVersion: Int,
        val episodeCount: Int,
        val packageDigest: String
    ) : ListeningPackageImportResult()

    data class AlreadyInstalled(
        val packageId: String,
        val packageVersion: Int
    ) : ListeningPackageImportResult()
}

/**
 * Installs a complete, rights-checked listening package.
 *
 * Audio is verified and moved to immutable revision-specific paths before the
 * single SQLite publication transaction. The current-revision pointers are
 * written last inside that transaction. A failed transaction can therefore
 * leave only unreferenced files, never a catalog pointer to incomplete audio.
 */
class ListeningContentPackageImporter(
    private val database: WordsDatabase,
    private val contentRoot: File,
    private val stagingRoot: File,
    private val appVersionCode: Int
) {
    fun importArchive(archive: File, nowMs: Long = System.currentTimeMillis()): ListeningPackageImportResult =
        synchronized(CONTENT_LOCK) { importArchiveLocked(archive, nowMs) }

    private fun importArchiveLocked(archive: File, nowMs: Long): ListeningPackageImportResult {
        require(archive.isFile && archive.canRead()) { "Package archive is not readable" }
        require(archive.length() in 1L..MAX_ARCHIVE_BYTES) { "Package archive exceeds the supported size" }
        require(appVersionCode > 0) { "appVersionCode must be positive" }
        require(nowMs > 0L) { "nowMs must be positive" }

        val packageDigest = sha256(archive)
        require(stagingRoot.exists() || stagingRoot.mkdirs()) { "Cannot create package staging root" }
        val staging = File(stagingRoot, "import-${UUID.randomUUID()}")
        val newlyInstalledFiles = mutableListOf<File>()
        var databaseCommitted = false

        try {
            val extracted = FileInputStream(archive).use { input ->
                ListeningPackageArchive.extractSafely(input, staging)
            }
            require(extracted.manifestFile.length() in 2L..MAX_MANIFEST_BYTES) {
                "Package manifest exceeds the supported size"
            }
            val manifest = ListeningContentPackageValidator.validate(
                extracted.manifestFile.readText(Charsets.UTF_8),
                appVersionCode
            )
            require(manifest.episodes.all { it.audio.path.isNotBlank() }) {
                "Every episode must declare an audio asset"
            }
            val audioPaths = manifest.episodes.map { it.audio.path }
            require(audioPaths.toSet().size == audioPaths.size) {
                "Each episode must use a unique audio path"
            }
            val declaredPaths = audioPaths.toSet()
            val extractedAudioPaths = extracted.files
                .filter { it != extracted.manifestFile }
                .map { it.relativeTo(staging).invariantSeparatorsPath }
                .toSet()
            require(extractedAudioPaths == declaredPaths) {
                "Archive audio entries do not exactly match the manifest"
            }

            val audioSources = manifest.episodes.associate { episode ->
                val file = ListeningAudioIntegrity.resolveUnderRoot(staging, episode.audio.path)
                require(ListeningAudioIntegrity.verify(file, episode.audio)) {
                    "Audio verification failed for episode ${episode.id}"
                }
                episode.id to file
            }

            val readDb = database.readableDatabase
            val existingPackage = readDb.rawQuery(
                "SELECT package_version,package_digest FROM listening_content_packages WHERE package_id=?",
                arrayOf(manifest.packageId)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) to cursor.getString(1) else null
            }
            if (existingPackage != null) {
                val (installedVersion, installedDigest) = existingPackage
                require(manifest.packageVersion >= installedVersion) {
                    "Package downgrade rejected: installed version is $installedVersion"
                }
                if (manifest.packageVersion == installedVersion) {
                    require(packageDigest == installedDigest) {
                        "Package version already exists with a different archive digest"
                    }
                    require(isInstalledPackageIntact(manifest, packageDigest)) {
                        "Installed package is incomplete or its immutable audio failed verification"
                    }
                    return ListeningPackageImportResult.AlreadyInstalled(
                        manifest.packageId, manifest.packageVersion
                    )
                }
            }

            val revisionFiles = manifest.episodes.associate { episode ->
                val revisionId = revisionId(manifest, episode.id, packageDigest)
                val extension = audioExtension(episode.audio.mimeType)
                val destinationDirectory = File(File(contentRoot, manifest.packageId), episode.id)
                val destination = File(destinationDirectory, "$revisionId.$extension")
                val canonicalRoot = contentRoot.canonicalFile
                require(destination.canonicalPath.startsWith(
                    canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
                )) { "Audio destination escapes the managed content directory" }

                if (destination.exists()) {
                    require(ListeningAudioIntegrity.verify(destination, episode.audio)) {
                        "An existing immutable revision audio file failed integrity verification"
                    }
                } else {
                    ListeningAudioIntegrity.copyAndVerifyNew(
                        FileInputStream(audioSources.getValue(episode.id)),
                        destination,
                        episode.audio
                    )
                    newlyInstalledFiles += destination
                }
                episode.id to (revisionId to destination)
            }

            val db = database.writableDatabase
            db.beginTransaction()
            try {
                val currentPackage = db.rawQuery(
                    "SELECT package_version,package_digest FROM listening_content_packages WHERE package_id=?",
                    arrayOf(manifest.packageId)
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) to cursor.getString(1) else null
                }
                require(currentPackage == existingPackage) {
                    "Package catalog changed during import; retry with the latest package state"
                }

                if (currentPackage == null) {
                    db.insertOrThrow(
                        "listening_content_packages", null,
                        ContentValues().apply {
                            put("package_id", manifest.packageId)
                            put("package_version", manifest.packageVersion)
                            put("package_digest", packageDigest)
                            put("content_revision", manifest.contentRevision)
                            put("imported_at", nowMs)
                        }
                    )
                } else {
                    db.update(
                        "listening_content_packages",
                        ContentValues().apply {
                            put("package_version", manifest.packageVersion)
                            put("package_digest", packageDigest)
                            put("content_revision", manifest.contentRevision)
                            put("imported_at", nowMs)
                        },
                        "package_id=?",
                        arrayOf(manifest.packageId)
                    )
                    db.update(
                        "listening_package_episodes",
                        ContentValues().apply { put("is_active", 0) },
                        "package_id=?",
                        arrayOf(manifest.packageId)
                    )
                }

                manifest.episodes.forEach { episode ->
                    val owner = db.rawQuery(
                        "SELECT package_id FROM listening_package_episodes WHERE episode_id=? LIMIT 1",
                        arrayOf(episode.id)
                    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    val existingEpisode = db.rawQuery(
                        "SELECT is_demo,status FROM listening_episodes WHERE id=? LIMIT 1",
                        arrayOf(episode.id)
                    ).use { cursor ->
                        if (cursor.moveToFirst()) (cursor.getInt(0) == 1) to cursor.getString(1) else null
                    }
                    require(
                        (existingEpisode == null && owner == null) ||
                            (existingEpisode != null && existingEpisode.first.not() &&
                                existingEpisode.second == "approved" && owner == manifest.packageId)
                    ) {
                        "Episode ID conflicts with existing content or is not owned by this package: ${episode.id}"
                    }
                    val revisionId = revisionFiles.getValue(episode.id).first
                    installRightsMetadata(db, episode, nowMs)
                    installEpisodeRevision(
                        db = db,
                        packageId = manifest.packageId,
                        episode = episode,
                        revisionId = revisionId,
                        packageDigest = packageDigest,
                        audioFile = revisionFiles.getValue(episode.id).second,
                        nowMs = nowMs
                    )
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            databaseCommitted = true
            return ListeningPackageImportResult.Installed(
                manifest.packageId, manifest.packageVersion, manifest.episodes.size, packageDigest
            )
        } catch (error: Exception) {
            if (!databaseCommitted) newlyInstalledFiles.forEach { file ->
                val referenced = runCatching {
                    database.readableDatabase.rawQuery(
                        "SELECT 1 FROM listening_episode_revisions WHERE audio_url=? LIMIT 1",
                        arrayOf(file.toURI().toString())
                    ).use { it.moveToFirst() }
                }.getOrDefault(true)
                if (!referenced) {
                    runCatching { file.delete() }
                    runCatching { file.parentFile?.delete() }
                }
            }
            throw error
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    /**
     * Removes only importer-owned revision audio files that are not referenced
     * by any immutable revision row. It never touches staging or .part files.
     */
    fun recoverOrphanedAudioFiles(): Int = synchronized(CONTENT_LOCK) {
        if (!contentRoot.exists()) return@synchronized 0
        val referenced = database.readableDatabase.rawQuery(
            "SELECT audio_url FROM listening_episode_revisions WHERE audio_url IS NOT NULL",
            null
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    runCatching { File(java.net.URI(cursor.getString(0))).canonicalPath }
                        .getOrNull()?.let(::add)
                }
            }
        }
        var removed = 0
        contentRoot.walkTopDown()
            .filter { it.isFile && REVISION_AUDIO_NAME.matches(it.name) }
            .forEach { file ->
                if (file.canonicalPath !in referenced && runCatching { file.delete() }.getOrDefault(false)) {
                    removed += 1
                }
            }
        removed
    }

    private fun isInstalledPackageIntact(
        manifest: ListeningContentPackage,
        packageDigest: String
    ): Boolean {
        val installed = database.readableDatabase.rawQuery(
            """SELECT pe.episode_id,pe.revision_id,r.audio_url
               FROM listening_package_episodes pe
               JOIN listening_episode_revisions r ON r.revision_id=pe.revision_id
               WHERE pe.package_id=? AND pe.is_active=1""",
            arrayOf(manifest.packageId)
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(0),
                        cursor.getString(1) to if (cursor.isNull(2)) null else cursor.getString(2)
                    )
                }
            }
        }
        if (installed.keys != manifest.episodes.map { it.id }.toSet()) return false
        return manifest.episodes.all { episode ->
            val row = installed[episode.id] ?: return@all false
            val expectedRevision = revisionId(manifest, episode.id, packageDigest)
            if (row.first != expectedRevision || row.second == null) return@all false
            val audioFile = runCatching { File(java.net.URI(row.second!!)) }.getOrNull()
                ?: return@all false
            ListeningAudioIntegrity.verify(audioFile, episode.audio)
        }
    }

    private fun installRightsMetadata(
        db: SQLiteDatabase,
        episode: ListeningPackageEpisode,
        nowMs: Long
    ) {
        val rights = episode.rights
        require(rights.expiresAt == null || rights.expiresAt > nowMs) {
            "Content license has expired for episode ${episode.id}"
        }
        require(rights.checkedAt <= nowMs + MAX_FUTURE_RIGHTS_CHECK_SKEW_MS) {
            "Rights verification timestamp is unexpectedly in the future"
        }

        db.insertWithOnConflict(
            "listening_sources", null,
            ContentValues().apply {
                put("id", rights.sourceId)
                put("title", rights.sourceTitle)
                put("source_url", rights.sourceUrl)
                put("rights_holder", rights.rightsHolder)
                put("attribution", rights.attribution)
                put("checked_at", rights.checkedAt)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        val sourceMatches = db.rawQuery(
            """SELECT title,source_url,rights_holder,attribution,checked_at
               FROM listening_sources WHERE id=?""",
            arrayOf(rights.sourceId)
        ).use { cursor ->
            cursor.moveToFirst() &&
                cursor.getString(0) == rights.sourceTitle &&
                cursor.getString(1) == rights.sourceUrl &&
                cursor.getString(2) == rights.rightsHolder &&
                cursor.getString(3) == rights.attribution &&
                cursor.getLong(4) == rights.checkedAt
        }
        require(sourceMatches) { "Conflicting source metadata for ${rights.sourceId}" }

        val allowedActions = rights.allowedActions.sorted().joinToString(",")
        db.insertWithOnConflict(
            "listening_licenses", null,
            ContentValues().apply {
                put("id", rights.licenseId)
                put("license_name", rights.licenseName)
                put("license_url", rights.licenseUrl)
                put("allowed_actions", allowedActions)
                put("territory", rights.territory)
                if (rights.expiresAt == null) putNull("expires_at") else put("expires_at", rights.expiresAt!!)
                put("notes", rights.attribution)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        val licenseMatches = db.rawQuery(
            """SELECT license_name,license_url,allowed_actions,territory,expires_at
               FROM listening_licenses WHERE id=?""",
            arrayOf(rights.licenseId)
        ).use { cursor ->
            cursor.moveToFirst() &&
                cursor.getString(0) == rights.licenseName &&
                cursor.getString(1) == rights.licenseUrl &&
                cursor.getString(2) == allowedActions &&
                cursor.getString(3) == rights.territory &&
                (if (cursor.isNull(4)) null else cursor.getLong(4)) == rights.expiresAt
        }
        require(licenseMatches) { "Conflicting license metadata for ${rights.licenseId}" }
    }

    private fun installEpisodeRevision(
        db: SQLiteDatabase,
        packageId: String,
        episode: ListeningPackageEpisode,
        revisionId: String,
        packageDigest: String,
        audioFile: File,
        nowMs: Long
    ) {
        val exists = db.rawQuery(
            "SELECT 1 FROM listening_episodes WHERE id=?",
            arrayOf(episode.id)
        ).use { it.moveToFirst() }

        val sourceOrdinal = nextOrdinal(db, "listening_sentences", episode.id)
        val questionOrdinal = nextOrdinal(db, "listening_questions", episode.id)
        val revisionToken = revisionId.removePrefix("rev_").take(20)
        val sentenceIds = episode.sentences.associate { it.id to "s_${revisionToken}_${it.id}" }
        val questionIds = episode.questions.associate { it.id to "q_${revisionToken}_${it.id}" }

        if (!exists) {
            db.insertOrThrow(
                "listening_episodes", null,
                ContentValues().apply {
                    put("id", episode.id)
                    put("title", episode.title)
                    put("description", episode.description)
                    put("category", episode.category)
                    put("level", episode.level)
                    put("duration_ms", episode.durationMs)
                    put("accent", episode.accent)
                    put("speech_rate", 1.0)
                    put("audio_url", audioFile.toURI().toString())
                    put("source_id", episode.rights.sourceId)
                    put("license_id", episode.rights.licenseId)
                    put("status", "approved")
                    put("is_demo", 0)
                    put("updated_at", nowMs)
                    put("current_revision_id", revisionId)
                }
            )
        }

        val revisionValues = ContentValues().apply {
            put("revision_id", revisionId)
            put("episode_id", episode.id)
            put("revision_hash", packageDigest)
            put("title", episode.title)
            put("description", episode.description)
            put("category", episode.category)
            put("level", episode.level)
            put("duration_ms", episode.durationMs)
            put("accent", episode.accent)
            put("speech_rate", 1.0)
            put("audio_url", audioFile.toURI().toString())
            put("is_demo", 0)
            put("updated_at", nowMs)
            put("created_at", nowMs)
        }
        db.insertOrThrow("listening_episode_revisions", null, revisionValues)

        episode.sentences.forEach { sentence ->
            val stableId = sentenceIds.getValue(sentence.id)
            db.insertOrThrow(
                "listening_sentences", null,
                ContentValues().apply {
                    put("id", stableId)
                    put("episode_id", episode.id)
                    put("ordinal", sourceOrdinal + sentence.ordinal)
                    put("start_ms", sentence.startMs)
                    put("end_ms", sentence.endMs)
                    put("english", sentence.english)
                    put("chinese", sentence.chinese)
                    put("pronunciation_note", sentence.pronunciationNote)
                }
            )
            db.insertOrThrow(
                "listening_revision_sentences", null,
                ContentValues().apply {
                    put("revision_id", revisionId)
                    put("sentence_id", stableId)
                    put("episode_id", episode.id)
                    put("ordinal", sentence.ordinal)
                    put("start_ms", sentence.startMs)
                    put("end_ms", sentence.endMs)
                    put("english", sentence.english)
                    put("chinese", sentence.chinese)
                    put("pronunciation_note", sentence.pronunciationNote)
                }
            )
        }

        episode.questions.forEach { question ->
            val stableQuestionId = questionIds.getValue(question.id)
            db.insertOrThrow(
                "listening_questions", null,
                ContentValues().apply {
                    put("id", stableQuestionId)
                    put("episode_id", episode.id)
                    put("ordinal", questionOrdinal + question.ordinal)
                    put("question_type", question.type)
                    put("prompt", question.prompt)
                    put("explanation", question.explanation)
                    put("evidence_sentence_id", sentenceIds.getValue(question.evidenceSentenceId))
                }
            )
            db.insertOrThrow(
                "listening_revision_questions", null,
                ContentValues().apply {
                    put("revision_id", revisionId)
                    put("question_id", stableQuestionId)
                    put("episode_id", episode.id)
                    put("ordinal", question.ordinal)
                    put("question_type", question.type)
                    put("prompt", question.prompt)
                    put("explanation", question.explanation)
                    put("evidence_sentence_id", sentenceIds.getValue(question.evidenceSentenceId))
                }
            )
            question.choices.forEachIndexed { index, choice ->
                val choiceId = db.insertOrThrow(
                    "listening_choices", null,
                    ContentValues().apply {
                        put("question_id", stableQuestionId)
                        put("ordinal", index + 1)
                        put("content", choice.content)
                        put("is_correct", if (choice.isCorrect) 1 else 0)
                    }
                )
                db.insertOrThrow(
                    "listening_revision_choices", null,
                    ContentValues().apply {
                        put("revision_id", revisionId)
                        put("question_id", stableQuestionId)
                        put("choice_id", choiceId)
                        put("ordinal", index + 1)
                        put("content", choice.content)
                        put("is_correct", if (choice.isCorrect) 1 else 0)
                    }
                )
            }
        }

        if (exists) {
            db.update(
                "listening_episodes",
                ContentValues().apply {
                    put("title", episode.title)
                    put("description", episode.description)
                    put("category", episode.category)
                    put("level", episode.level)
                    put("duration_ms", episode.durationMs)
                    put("accent", episode.accent)
                    put("speech_rate", 1.0)
                    put("audio_url", audioFile.toURI().toString())
                    put("source_id", episode.rights.sourceId)
                    put("license_id", episode.rights.licenseId)
                    put("status", "approved")
                    put("is_demo", 0)
                    put("updated_at", nowMs)
                },
                "id=?",
                arrayOf(episode.id)
            )
        }

        val mapping = ContentValues().apply {
            put("package_id", packageId)
            put("episode_id", episode.id)
            put("revision_id", revisionId)
            put("is_active", 1)
        }
        val updatedMapping = db.update(
            "listening_package_episodes",
            mapping,
            "package_id=? AND episode_id=?",
            arrayOf(packageId, episode.id)
        )
        if (updatedMapping == 0) db.insertOrThrow("listening_package_episodes", null, mapping)

        // Publish only after every immutable row and verified audio file exists.
        db.update(
            "listening_episodes",
            ContentValues().apply { put("current_revision_id", revisionId) },
            "id=?",
            arrayOf(episode.id)
        )
    }

    private fun nextOrdinal(db: SQLiteDatabase, table: String, episodeId: String): Int {
        require(table == "listening_sentences" || table == "listening_questions")
        return db.rawQuery(
            "SELECT COALESCE(MAX(ordinal),0) FROM $table WHERE episode_id=?",
            arrayOf(episodeId)
        ).use { cursor ->
            require(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    private fun revisionId(
        packageData: ListeningContentPackage,
        episodeId: String,
        packageDigest: String
    ): String = "rev_" + sha256(
        "${packageData.packageId}|${packageData.packageVersion}|${packageData.contentRevision}|$episodeId|$packageDigest"
    ).take(40)

    private fun audioExtension(mimeType: String): String = when (mimeType.lowercase()) {
        "audio/mpeg" -> "mp3"
        "audio/mp4" -> "m4a"
        "audio/aac" -> "aac"
        "audio/ogg" -> "ogg"
        "audio/opus" -> "opus"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/flac" -> "flac"
        else -> throw IllegalArgumentException("Unsupported audio MIME type: $mimeType")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val CONTENT_LOCK = Any()
        private const val MAX_MANIFEST_BYTES = 1L * 1024L * 1024L
        private const val MAX_ARCHIVE_BYTES = 1_100_000_000L
        private const val MAX_FUTURE_RIGHTS_CHECK_SKEW_MS = 24L * 60L * 60L * 1000L
        private val REVISION_AUDIO_NAME = Regex("rev_[a-f0-9]{40}\\.(mp3|m4a|aac|ogg|opus|wav|flac)")
    }
}
