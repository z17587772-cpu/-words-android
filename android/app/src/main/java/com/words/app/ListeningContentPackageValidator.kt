package com.words.app

import org.json.JSONArray
import org.json.JSONObject

data class ListeningContentPackage(
    val packageId: String,
    val packageVersion: Int,
    val minimumAppVersionCode: Int,
    val contentRevision: String,
    val episodes: List<ListeningPackageEpisode>
)

data class ListeningPackageEpisode(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val level: String,
    val durationMs: Long,
    val accent: String,
    val speaker: String,
    val audio: ListeningPackageAudio,
    val rights: ListeningPackageRights,
    val sentences: List<ListeningPackageSentence>,
    val questions: List<ListeningPackageQuestion>
)

data class ListeningPackageAudio(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val mimeType: String,
    val language: String
)

data class ListeningPackageRights(
    val sourceId: String,
    val rightsHolder: String,
    val licenseName: String,
    val licenseUrl: String,
    val allowedActions: Set<String>,
    val territory: String,
    val expiresAt: Long?
)

data class ListeningPackageSentence(
    val id: String,
    val ordinal: Int,
    val startMs: Long,
    val endMs: Long,
    val english: String,
    val chinese: String,
    val pronunciationNote: String
)

data class ListeningPackageChoice(val content: String, val isCorrect: Boolean)

data class ListeningPackageQuestion(
    val id: String,
    val ordinal: Int,
    val type: String,
    val prompt: String,
    val explanation: String,
    val evidenceSentenceId: String,
    val choices: List<ListeningPackageChoice>
)

/**
 * Validates the complete, versioned content manifest before any database or
 * file mutation is allowed. Audio bytes are verified separately at import time.
 */
object ListeningContentPackageValidator {
    private val idPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{1,79}")
    private val sha256Pattern = Regex("[a-fA-F0-9]{64}")
    private val questionTypes = setOf(
        "main_idea", "detail", "inference", "attitude",
        "pronunciation", "dictation", "connected_speech", "note_taking", "shadowing"
    )

    fun validate(json: String, appVersionCode: Int): ListeningContentPackage {
        require(appVersionCode > 0) { "appVersionCode must be positive" }
        val root = JSONObject(json)
        val schemaVersion = root.requiredInt("schema_version", "package")
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported listening package schema_version=$schemaVersion"
        }

        val packageId = root.requiredString("package_id", "package")
        require(idPattern.matches(packageId)) { "package.package_id is invalid" }
        val packageVersion = root.requiredInt("package_version", "package")
        require(packageVersion > 0) { "package.package_version must be positive" }
        val minimumAppVersion = root.requiredInt("minimum_app_version_code", "package")
        require(minimumAppVersion > 0) { "package.minimum_app_version_code must be positive" }
        require(appVersionCode >= minimumAppVersion) {
            "Package requires app versionCode $minimumAppVersion"
        }
        val revision = root.requiredString("content_revision", "package")
        val episodesJson = root.requiredArray("episodes", "package")
        require(episodesJson.length() > 0) { "package.episodes must not be empty" }

        val episodeIds = mutableSetOf<String>()
        val episodes = (0 until episodesJson.length()).map { index ->
            val path = "package.episodes[$index]"
            val episode = episodesJson.getJSONObject(index)
            val id = episode.requiredString("id", path)
            require(idPattern.matches(id) && episodeIds.add(id)) { "$path.id is invalid or duplicated" }

            val level = episode.requiredString("level", path)
            require(level in setOf("L1", "L2", "L3", "L4")) { "$path.level must be L1-L4" }
            val duration = episode.requiredLong("duration_ms", path)
            require(duration > 0L) { "$path.duration_ms must be positive" }

            val audio = parseAudio(episode.getJSONObject("audio"), "$path.audio")
            val rights = parseRights(episode.getJSONObject("rights"), "$path.rights")
            val sentences = parseSentences(episode.requiredArray("sentences", path), "$path.sentences", duration)
            val sentenceIds = sentences.map { it.id }.toSet()
            val questions = parseQuestions(episode.requiredArray("questions", path), "$path.questions", sentenceIds)

            ListeningPackageEpisode(
                id = id,
                title = episode.requiredString("title", path),
                description = episode.optString("description", ""),
                category = episode.requiredString("category", path),
                level = level,
                durationMs = duration,
                accent = episode.requiredString("accent", path),
                speaker = episode.requiredString("speaker", path),
                audio = audio,
                rights = rights,
                sentences = sentences,
                questions = questions
            )
        }

        return ListeningContentPackage(
            packageId = packageId,
            packageVersion = packageVersion,
            minimumAppVersionCode = minimumAppVersion,
            contentRevision = revision,
            episodes = episodes
        )
    }

    private fun parseAudio(json: JSONObject, path: String): ListeningPackageAudio {
        val audioPath = json.requiredString("path", path)
        val segments = audioPath.split("/")
        require(!audioPath.startsWith("/") && !audioPath.contains("\\") &&
            segments.none { it.isBlank() || it == "." || it == ".." }
        ) { "$path.path must be a safe relative path" }
        val size = json.requiredLong("size_bytes", path)
        require(size > 0L) { "$path.size_bytes must be positive" }
        val checksum = json.requiredString("sha256", path)
        require(sha256Pattern.matches(checksum)) { "$path.sha256 must be a 64-character hex digest" }
        val mime = json.requiredString("mime_type", path)
        require(mime.startsWith("audio/")) { "$path.mime_type must be audio/*" }
        val language = json.requiredString("language", path)
        require(language.startsWith("en", ignoreCase = true)) { "$path.language must identify English audio" }
        return ListeningPackageAudio(audioPath, size, checksum.lowercase(), mime, language)
    }

    private fun parseRights(json: JSONObject, path: String): ListeningPackageRights {
        val actionsJson = json.requiredArray("allowed_actions", path)
        val actions = (0 until actionsJson.length()).map { actionsJson.getString(it) }.toSet()
        require("in_app_playback" in actions) { "$path.allowed_actions must permit in_app_playback" }
        val expiry = if (json.has("expires_at") && !json.isNull("expires_at")) {
            json.getLong("expires_at").also { require(it > 0L) { "$path.expires_at must be positive" } }
        } else null
        return ListeningPackageRights(
            sourceId = json.requiredString("source_id", path),
            rightsHolder = json.requiredString("rights_holder", path),
            licenseName = json.requiredString("license_name", path),
            licenseUrl = json.optString("license_url", ""),
            allowedActions = actions,
            territory = json.requiredString("territory", path),
            expiresAt = expiry
        )
    }

    private fun parseSentences(
        array: JSONArray,
        path: String,
        durationMs: Long
    ): List<ListeningPackageSentence> {
        require(array.length() > 0) { "$path must not be empty" }
        val ids = mutableSetOf<String>()
        var previousStart = -1L
        return (0 until array.length()).map { index ->
            val itemPath = "$path[$index]"
            val item = array.getJSONObject(index)
            val id = item.requiredString("id", itemPath)
            require(idPattern.matches(id) && ids.add(id)) { "$itemPath.id is invalid or duplicated" }
            val ordinal = item.requiredInt("ordinal", itemPath)
            require(ordinal == index + 1) { "$itemPath.ordinal must be sequential from 1" }
            val start = item.requiredLong("start_ms", itemPath)
            val end = item.requiredLong("end_ms", itemPath)
            require(start >= 0L && end > start && end <= durationMs) {
                "$itemPath timestamps must satisfy 0 <= start < end <= duration"
            }
            require(start >= previousStart) { "$itemPath.start_ms must be chronological" }
            previousStart = start
            ListeningPackageSentence(
                id, ordinal, start, end,
                item.requiredString("english", itemPath),
                item.requiredString("chinese", itemPath),
                item.optString("pronunciation_note", "")
            )
        }
    }

    private fun parseQuestions(
        array: JSONArray,
        path: String,
        sentenceIds: Set<String>
    ): List<ListeningPackageQuestion> {
        require(array.length() in 1..8) { "$path must contain 1-8 questions" }
        val ids = mutableSetOf<String>()
        return (0 until array.length()).map { index ->
            val itemPath = "$path[$index]"
            val item = array.getJSONObject(index)
            val id = item.requiredString("id", itemPath)
            require(idPattern.matches(id) && ids.add(id)) { "$itemPath.id is invalid or duplicated" }
            val ordinal = item.requiredInt("ordinal", itemPath)
            require(ordinal == index + 1) { "$itemPath.ordinal must be sequential from 1" }
            val type = item.requiredString("type", itemPath)
            require(type in questionTypes) { "$itemPath.type is unsupported" }
            val evidence = item.requiredString("evidence_sentence_id", itemPath)
            require(evidence in sentenceIds) { "$itemPath references an unknown evidence sentence" }
            val choicesJson = item.requiredArray("choices", itemPath)
            require(choicesJson.length() in 2..6) { "$itemPath.choices must contain 2-6 choices" }
            val choices = (0 until choicesJson.length()).map { choiceIndex ->
                val choicePath = "$itemPath.choices[$choiceIndex]"
                val choice = choicesJson.getJSONObject(choiceIndex)
                require(choice.has("is_correct") && !choice.isNull("is_correct")) {
                    "$choicePath.is_correct is required"
                }
                ListeningPackageChoice(
                    choice.requiredString("content", choicePath),
                    choice.getBoolean("is_correct")
                )
            }
            require(choices.count { it.isCorrect } == 1) {
                "$itemPath must contain exactly one correct choice"
            }
            ListeningPackageQuestion(
                id, ordinal, type,
                item.requiredString("prompt", itemPath),
                item.requiredString("explanation", itemPath),
                evidence, choices
            )
        }
    }

    private fun JSONObject.requiredString(key: String, path: String): String {
        require(has(key) && !isNull(key)) { "$path.$key is required" }
        val value = getString(key).trim()
        require(value.isNotEmpty()) { "$path.$key must not be blank" }
        return value
    }

    private fun JSONObject.requiredInt(key: String, path: String): Int {
        require(has(key) && !isNull(key)) { "$path.$key is required" }
        return getInt(key)
    }

    private fun JSONObject.requiredLong(key: String, path: String): Long {
        require(has(key) && !isNull(key)) { "$path.$key is required" }
        return getLong(key)
    }

    private fun JSONObject.requiredArray(key: String, path: String): JSONArray {
        require(has(key) && !isNull(key)) { "$path.$key is required" }
        return getJSONArray(key)
    }

    const val SUPPORTED_SCHEMA_VERSION = 1
}
