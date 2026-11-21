package com.words.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Internet Archive discovery + download lane. Only item-level CC0 / CC-BY 4.0 /
 * CC-BY-SA 4.0 metadata is accepted for automatic offline copies. Hosting alone
 * never grants rights. The raw library stays separate from authored lessons.
 */
object ListeningResourceAutomation {
    private const val IA_PERIODIC = "listening-internet-archive-discovery"
    private const val IA_NOW = "listening-internet-archive-discovery-now"

    fun schedule(context: Context) {
        val discoveryConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val downloadConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        val periodic = PeriodicWorkRequestBuilder<InternetArchiveDiscoveryWorker>(24, TimeUnit.HOURS)
            .setConstraints(discoveryConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            IA_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic
        )
        val now = OneTimeWorkRequestBuilder<InternetArchiveDiscoveryWorker>()
            .setConstraints(discoveryConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(IA_NOW, ExistingWorkPolicy.KEEP, now)
    }

    fun enqueueDownload(context: Context, resourceId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<ListeningResourceDownloadWorker>()
            .setInputData(Data.Builder().putString(ListeningResourceDownloadWorker.KEY_RESOURCE_ID, resourceId).build())
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // One serial chain prevents concurrent workers from racing the storage quota.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "listening-resource-download-queue",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }
}

class InternetArchiveDiscoveryWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val helper = WordsDatabase(applicationContext)
        val repository = ListeningDiscoveryRepository(helper)
        val now = System.currentTimeMillis()
        val previous = repository.getProviderState(PROVIDER)
        if (previous != null && previous.backoffUntil > now) {
            helper.close()
            return Result.retry()
        }
        return try {
            var discovered = 0
            // Always revisit newest results, plus a bounded rotating backfill.
            val cursor = runCatching { JSONObject(previous?.cursorJson ?: "{}") }.getOrDefault(JSONObject())
            val backfillPage = cursor.optInt("next_page", 1).coerceAtLeast(1)
            val pages = linkedSetOf(1, backfillPage.coerceIn(1, MAX_BACKFILL_PAGE), (backfillPage + 1).coerceAtMost(MAX_BACKFILL_PAGE)).take(MAX_SEARCH_PAGES)
            for (page in pages) {
                val docs = searchPage(page)
                for (doc in docs) {
                    val identifier = doc.optString("identifier").trim()
                    if (!identifier.matches(Regex("[A-Za-z0-9._-]{1,120}"))) continue
                    val license = InternetArchiveMetadataPolicy.normalizeLicense(doc.optString("licenseurl"))
                    if (license == null) continue
                    val detail = fetchMetadata(identifier)
                    val metadata = detail.optJSONObject("metadata") ?: continue
                    val detailLicense = InternetArchiveMetadataPolicy.normalizeLicense(metadata.optString("licenseurl"))
                    if (detailLicense != license) continue
                    val languageValue = metadata.opt("language") ?: doc.opt("language")
                    if (!InternetArchiveMetadataPolicy.isEnglish(languageValue)) continue
                    val title = metadata.optString("title").ifBlank { doc.optString("title") }.trim()
                    if (title.isBlank()) continue
                    val files = detail.optJSONArray("files") ?: continue
                    val selected = selectAudioFiles(files)
                    for (file in selected) {
                        val fileName = file.optString("name")
                        val size = file.optString("size").toLongOrNull() ?: 0L
                        if (fileName.isBlank() || size !in MIN_FILE_BYTES..MAX_FILE_BYTES) continue
                        val encodedName = Uri.encode(fileName)
                        val mediaUrl = "https://archive.org/download/$identifier/$encodedName"
                        val creator = metadata.optString("creator").take(500)
                        val itemTitle = if (selected.size > 1) "$title · ${fileName.substringAfterLast('/').substringBeforeLast('.')}" else title
                        val candidate = DiscoveredListeningResource(
                            provider = PROVIDER,
                            providerItemId = "$identifier#$fileName",
                            canonicalUrl = "https://archive.org/details/$identifier",
                            mediaUrl = mediaUrl,
                            title = itemTitle.take(500),
                            description = metadata.optString("description").take(3000),
                            language = "English",
                            durationMs = 0L,
                            creator = creator,
                            licenseName = license.second,
                            licenseUrl = license.first,
                            rightsStatus = "cleared",
                            allowedActions = setOf("in_app_playback", "offline_download"),
                            attribution = "Source: Internet Archive item $identifier; ${license.second}; retain source attribution",
                            territory = "CC license grant",
                            mimeType = "audio/mpeg",
                            expectedSizeBytes = size
                        )
                        val id = repository.upsertCandidate(candidate, now)
                        if (repository.queueDownload(id)) {
                            ListeningResourceAutomation.enqueueDownload(applicationContext, id)
                        }
                        discovered++
                    }
                    if (discovered >= MAX_CANDIDATES_PER_RUN) break
                    Thread.sleep(DETAIL_DELAY_MS)
                }
                if (docs.size < PAGE_SIZE || discovered >= MAX_CANDIDATES_PER_RUN) break
                Thread.sleep(SEARCH_DELAY_MS)
            }
            repository.saveProviderState(
                ListeningProviderState(
                    provider = PROVIDER,
                    cursorJson = JSONObject().put("next_page", if (backfillPage + 2 > MAX_BACKFILL_PAGE) 1 else backfillPage + 2).toString(),
                    lastSuccessAt = now,
                    lastAttemptAt = now
                )
            )
            Result.success()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.retry()
        } catch (error: Exception) {
            repository.saveProviderState(
                ListeningProviderState(
                    provider = PROVIDER,
                    cursorJson = previous?.cursorJson ?: "{}",
                    lastSuccessAt = previous?.lastSuccessAt ?: 0L,
                    lastAttemptAt = now,
                    backoffUntil = now + ERROR_BACKOFF_MS,
                    lastError = error.message.orEmpty()
                )
            )
            Result.retry()
        } finally {
            helper.close()
        }
    }

    private fun searchPage(page: Int): List<JSONObject> {
        val query = "mediatype:audio AND language:eng"
        val uri = Uri.parse("https://archive.org/advancedsearch.php").buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("fl[]", "identifier")
            .appendQueryParameter("fl[]", "title")
            .appendQueryParameter("fl[]", "description")
            .appendQueryParameter("fl[]", "creator")
            .appendQueryParameter("fl[]", "licenseurl")
            .appendQueryParameter("fl[]", "language")
            .appendQueryParameter("rows", PAGE_SIZE.toString())
            .appendQueryParameter("page", page.toString())
            .appendQueryParameter("sort[]", "publicdate desc")
            .appendQueryParameter("output", "json")
            .build()
        val root = JSONObject(String(readHttps(uri.toString(), setOf("archive.org")), Charsets.UTF_8))
        val docs = root.optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        return (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }
    }

    private fun fetchMetadata(identifier: String): JSONObject {
        val url = "https://archive.org/metadata/$identifier"
        return JSONObject(String(readHttps(url, setOf("archive.org")), Charsets.UTF_8))
    }

    private fun readHttps(url: String, hosts: Set<String>): ByteArray {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) { hop ->
            require(current.protocol.equals("https", true) && (current.host.lowercase() in hosts || current.host.equals("www.archive.org", true))) {
                "Internet Archive redirected outside the approved API host"
            }
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw java.io.IOException("Missing redirect location")
                    current = URL(current, location)
                    if (hop == MAX_REDIRECTS) throw java.io.IOException("Too many redirects")
                } else {
                    if (code == 429 || code in 500..599) throw java.io.IOException("Internet Archive temporary HTTP $code")
                    require(code == 200) { "Internet Archive returned HTTP $code" }
                    return connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_JSON_BYTES) { "Metadata response exceeded size limit" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw java.io.IOException("Unable to fetch Internet Archive metadata")
    }

    private fun selectAudioFiles(files: JSONArray): List<JSONObject> {
        val candidates = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
            .filter { file ->
                val name = file.optString("name")
                val format = file.optString("format")
                name.lowercase().endsWith(".mp3") &&
                    (format.contains("mp3", true) || format.contains("mpeg", true)) &&
                    (file.optString("size").toLongOrNull() ?: 0L) in MIN_FILE_BYTES..MAX_FILE_BYTES &&
                    !file.optString("source").equals("metadata", true) &&
                    !file.optString("private").equals("true", true)
            }
            .sortedBy { it.optString("name") }
        return candidates.take(MAX_TRACKS_PER_ITEM)
    }


    companion object {
        private const val PROVIDER = "internet_archive"
        private const val USER_AGENT = "WordsAndroidListeningDiscovery/1.0 (metadata and licensed audio)"
        private const val PAGE_SIZE = 50
        private const val MAX_SEARCH_PAGES = 3
        private const val MAX_BACKFILL_PAGE = 50
        private const val MAX_CANDIDATES_PER_RUN = 5
        private const val MAX_TRACKS_PER_ITEM = 5
        private const val MIN_FILE_BYTES = 64 * 1024L
        private const val MAX_FILE_BYTES = 20L * 1024L * 1024L
        private const val MAX_LIBRARY_BYTES = 512L * 1024L * 1024L
        private const val MAX_JSON_BYTES = 6 * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val SEARCH_DELAY_MS = 2_000L
        private const val DETAIL_DELAY_MS = 1_000L
        private const val ERROR_BACKOFF_MS = 30L * 60L * 1000L
        private const val MAX_REDIRECTS = 4
    }
}

internal object InternetArchiveMetadataPolicy {
    fun isEnglish(value: Any?): Boolean {
        val language = when (value) {
            is JSONArray -> (0 until value.length()).joinToString(" ") { value.optString(it) }
            else -> value?.toString().orEmpty()
        }
        return Regex("(^|[^a-z])(eng|english|en)([^a-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(language)
    }

    fun normalizeLicense(raw: String): Pair<String, String>? {
        val url = raw.trim().lowercase().trimEnd('/') + "/"
        return when (url) {
            "https://creativecommons.org/publicdomain/zero/1.0/" ->
                "https://creativecommons.org/publicdomain/zero/1.0/" to "CC0 1.0"
            "https://creativecommons.org/licenses/by/4.0/" ->
                "https://creativecommons.org/licenses/by/4.0/" to "CC BY 4.0"
            "https://creativecommons.org/licenses/by-sa/4.0/" ->
                "https://creativecommons.org/licenses/by-sa/4.0/" to "CC BY-SA 4.0"
            else -> null
        }
    }
}

class ListeningResourceDownloadWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getString(KEY_RESOURCE_ID) ?: return Result.failure()
        val helper = WordsDatabase(applicationContext)
        val repository = ListeningDiscoveryRepository(helper)
        val candidate = repository.getDownloadCandidate(id) ?: return Result.failure()
        if (candidate.status == "downloaded") return Result.success()
        if (candidate.rightsStatus != "cleared" ||
            !candidate.allowedActions.contains("offline_download") ||
            candidate.status !in setOf("queued", "downloading", "failed") ||
            candidate.expectedSizeBytes !in 1L..MAX_FILE_BYTES ||
            candidate.mimeType != "audio/mpeg"
        ) return Result.failure()

        val root = File(applicationContext.filesDir, "listening-library")
        if (!root.exists() && !root.mkdirs()) return Result.retry()
        if (!ensureStorageCapacity(repository, root, candidate.expectedSizeBytes)) {
            repository.updateDownloadStatus(id, "failed")
            return Result.failure()
        }
        val temporary = File(root, ".$id-${System.currentTimeMillis()}.part")
        var installedFile: File? = null
        repository.updateDownloadStatus(id, "downloading")
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val response = openMedia(candidate.mediaUrl, candidate.expectedSizeBytes)
            try {
                response.first.use { input ->
                    FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        if (isStopped) throw InterruptedException("Download worker stopped")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        require(total <= candidate.expectedSizeBytes && total <= MAX_FILE_BYTES) {
                            "Audio exceeds declared size limit"
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                        output.fd.sync()
                    }
                }
            } finally {
                response.second.disconnect()
            }
            require(total == candidate.expectedSizeBytes) { "Audio size differs from provider metadata" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val finalFile = File(root, "$id-$hash.mp3")
            val retriever = MediaMetadataRetriever()
            val duration = try {
                retriever.setDataSource(temporary.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
            require(duration > 0L) { "Downloaded audio is not a playable media file" }
            if (finalFile.exists()) {
                require(finalFile.length() == total && sha256(finalFile) == hash) {
                    "Existing immutable audio revision is corrupt"
                }
                temporary.delete()
            } else {
                try {
                    java.nio.file.Files.move(temporary.toPath(), finalFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(temporary.toPath(), finalFile.toPath())
                }
                installedFile = finalFile
            }
            require(finalFile.isFile && finalFile.length() == total && sha256(finalFile) == hash) {
                "Published audio failed verification"
            }
            repository.saveLocalFile(id, finalFile.absolutePath, hash, duration)
            installedFile = null
            Result.success()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            repository.updateDownloadStatus(id, "queued")
            Result.retry()
        } catch (error: Exception) {
            temporary.delete()
            installedFile?.let { runCatching { it.delete() } }
            repository.updateDownloadStatus(id, "failed")
            if (error is java.io.IOException) Result.retry() else Result.failure()
        } finally {
            helper.close()
        }
    }

    private fun ensureStorageCapacity(
        repository: ListeningDiscoveryRepository,
        root: File,
        incomingBytes: Long
    ): Boolean {
        val quota = MAX_LIBRARY_BYTES
        var used = repository.downloadedStorageBytes()
        if (used + incomingBytes <= quota) return true
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        for (candidate in repository.getEvictionCandidates()) {
            val file = if (candidate.localFilePath.isBlank()) null else runCatching { File(candidate.localFilePath).canonicalFile }.getOrNull()
            if (file != null && !file.path.startsWith(rootPath)) continue
            val bytes = file?.takeIf { it.isFile }?.length() ?: candidate.expectedSizeBytes
            if (file != null && file.exists() && !file.delete()) continue
            if (repository.markLocalFileEvicted(candidate.id, candidate.localFilePath)) {
                used = (used - bytes).coerceAtLeast(0L)
                if (used + incomingBytes <= quota) return true
            }
        }
        return used + incomingBytes <= quota
    }

    private fun openMedia(rawUrl: String, expectedSize: Long): Pair<java.io.InputStream, HttpURLConnection> {
        var current = URL(rawUrl)
        repeat(MAX_REDIRECTS + 1) { hop ->
            require(current.protocol.equals("https", true) && isArchiveHost(current.host)) {
                "Media URL escaped approved Archive.org hosts"
            }
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "audio/mpeg, application/octet-stream")
            val code = try { connection.responseCode } catch (error: Exception) { connection.disconnect(); throw error }
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: throw java.io.IOException("Missing media redirect")
                connection.disconnect()
                current = URL(current, location)
                if (hop == MAX_REDIRECTS) throw java.io.IOException("Too many media redirects")
            } else {
                if (code == 429 || code in 500..599) {
                    connection.disconnect()
                    throw java.io.IOException("Audio host temporarily returned HTTP $code")
                }
                if (code != 200) {
                    connection.disconnect()
                    throw IllegalStateException("Audio host returned HTTP $code")
                }
                val length = connection.getHeaderField("Content-Length")?.toLongOrNull()
                if (length != null && length != expectedSize) {
                    connection.disconnect()
                    throw IllegalArgumentException("Audio Content-Length mismatch")
                }
                return try {
                    connection.inputStream to connection
                } catch (error: Exception) {
                    connection.disconnect()
                    throw error
                }
            }
        }
        throw java.io.IOException("Unable to open media URL")
    }


    private fun isArchiveHost(host: String): Boolean {
        val normalized = host.lowercase()
        return normalized == "archive.org" || normalized.endsWith(".archive.org")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_RESOURCE_ID = "resource_id"
        private const val USER_AGENT = "WordsAndroidListeningLibrary/1.0"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_FILE_BYTES = 40L * 1024L * 1024L
    }
}
