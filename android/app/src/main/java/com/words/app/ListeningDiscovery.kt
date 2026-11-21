package com.words.app

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * On-device metadata discovery. This is deliberately independent of the
 * signed-catalog package publisher and never auto-downloads content whose
 * item/territory rights have not been cleared.
 */
object ListeningDiscoveryScheduler {
    private const val PERIODIC_WORK = "listening-online-discovery"
    private const val IMMEDIATE_WORK = "listening-online-discovery-now"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val periodic = PeriodicWorkRequestBuilder<LibriVoxDiscoveryWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic
        )
        val immediate = OneTimeWorkRequestBuilder<LibriVoxDiscoveryWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK, ExistingWorkPolicy.KEEP, immediate
        )
    }
}

class LibriVoxDiscoveryWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val repository = ListeningDiscoveryRepository(WordsDatabase(applicationContext))
        val now = System.currentTimeMillis()
        val state = repository.getProviderState(PROVIDER)
        if (state != null && state.backoffUntil > now) return Result.retry()

        val cursor = runCatching { JSONObject(state?.cursorJson ?: "{}") }.getOrElse {
            return Result.failure()
        }
        val previousSuccess = state?.lastSuccessAt ?: 0L
        val sinceEpoch = if (cursor.has("query_since_epoch")) {
            cursor.optLong("query_since_epoch")
        } else if (previousSuccess > 0L) {
            (previousSuccess / 1000L) - OVERLAP_SECONDS
        } else {
            // Initial backfill is intentionally bounded to the recent catalog;
            // later daily runs continuously add newly catalogued recordings.
            (now / 1000L) - INITIAL_BACKFILL_SECONDS
        }
        var offset = cursor.optInt("next_offset", 0).coerceAtLeast(0)
        var pages = 0
        return try {
            while (pages < MAX_PAGES_PER_RUN) {
                val records = fetchPage(sinceEpoch, offset)
                records.forEach { raw ->
                    LibriVoxMetadataParser.normalize(raw)?.let { repository.upsertCandidate(it, now) }
                }
                pages++
                offset += records.size
                if (records.size < PAGE_SIZE) {
                    repository.saveProviderState(
                        ListeningProviderState(
                            provider = PROVIDER,
                            cursorJson = JSONObject().put("next_offset", 0).toString(),
                            lastSuccessAt = now,
                            lastAttemptAt = now
                        )
                    )
                    return Result.success()
                }
                // The upstream asks bulk API consumers to leave several
                // seconds between pages; never hammer the volunteer service.
                if (pages < MAX_PAGES_PER_RUN) Thread.sleep(PAGE_DELAY_MS)
            }
            repository.saveProviderState(
                ListeningProviderState(
                    provider = PROVIDER,
                    cursorJson = JSONObject()
                        .put("query_since_epoch", sinceEpoch)
                        .put("next_offset", offset)
                        .toString(),
                    lastSuccessAt = previousSuccess,
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
                    cursorJson = JSONObject()
                        .put("query_since_epoch", sinceEpoch)
                        .put("next_offset", offset)
                        .toString(),
                    lastSuccessAt = previousSuccess,
                    lastAttemptAt = now,
                    backoffUntil = now + ERROR_BACKOFF_MS,
                    lastError = error.message.orEmpty()
                )
            )
            Result.retry()
        }
    }

    internal fun fetchPage(sinceEpoch: Long, offset: Int): List<JSONObject> {
        val uri = Uri.parse(API_URL).buildUpon()
            .appendQueryParameter("format", "json")
            .appendQueryParameter("extended", "0")
            .appendQueryParameter("limit", PAGE_SIZE.toString())
            .appendQueryParameter("offset", offset.toString())
            .appendQueryParameter("sort_order", "desc")
            .appendQueryParameter("since", sinceEpoch.toString())
            .build()
        val connection = URL(uri.toString()).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            if (code == 429 || code in 500..599) throw java.io.IOException("LibriVox temporarily returned HTTP $code")
            if (code != 200) throw IllegalStateException("LibriVox returned HTTP $code")
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_RESPONSE_BYTES) { "LibriVox response exceeded size limit" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return LibriVoxMetadataParser.extractRecords(JSONObject(String(bytes, Charsets.UTF_8)))
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val PROVIDER = "librivox"
        private const val API_URL = "https://librivox.org/api/feed/audiobooks/"
        private const val PUBLIC_DOMAIN_INFO = "https://librivox.org/pages/public-domain/"
        private const val USER_AGENT = "WordsAndroidListeningDiscovery/1.0"
        private const val PAGE_SIZE = 50
        private const val MAX_PAGES_PER_RUN = 5
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val PAGE_DELAY_MS = 3_000L
        private const val OVERLAP_SECONDS = 3L * 24L * 60L * 60L
        private const val INITIAL_BACKFILL_SECONDS = 30L * 24L * 60L * 60L
        private const val ERROR_BACKOFF_MS = 15L * 60L * 1000L
    }
}

internal object LibriVoxMetadataParser {
    fun extractRecords(root: JSONObject): List<JSONObject> {
        val value = when {
            root.opt("books") is JSONArray -> root.optJSONArray("books")
            root.opt("book") is JSONArray -> root.optJSONArray("book")
            root.opt("audiobooks") is JSONArray -> root.optJSONArray("audiobooks")
            root.opt("book") is JSONObject -> JSONArray().put(root.optJSONObject("book"))
            else -> JSONArray()
        } ?: JSONArray()
        return (0 until value.length()).mapNotNull { value.optJSONObject(it) }
    }

    fun normalize(raw: JSONObject): DiscoveredListeningResource? {
        val providerId = raw.optString("id").takeIf { it.matches(Regex("[0-9]{1,12}")) } ?: return null
        val title = raw.optString("title").trim().takeIf { it.isNotEmpty() } ?: return null
        val language = raw.optString("language").trim()
        if (!language.equals("English", ignoreCase = true)) return null
        val canonical = raw.optString("url_librivox").trim()
        val zip = raw.optString("url_zip_file").trim()
        val rss = raw.optString("url_rss").trim()
        if (!isAllowedHttps(canonical, setOf("librivox.org", "www.librivox.org"))) return null
        val mediaUrl = when {
            isAllowedHttps(zip, setOf("archive.org", "www.archive.org")) -> zip
            isAllowedHttps(rss, setOf("librivox.org", "www.librivox.org")) -> rss
            else -> ""
        }
        val durationSeconds = raw.optLong("totaltimesecs", 0L).coerceAtLeast(0L)
        val authors = raw.optJSONArray("authors")
        val creator = authors?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { author ->
                    listOf(author.optString("first_name"), author.optString("last_name"))
                        .filter { it.isNotBlank() }.joinToString(" ")
                }
            }.filter { it.isNotBlank() }.joinToString(", ")
        } ?: raw.optJSONObject("authors")?.let { author ->
            listOf(author.optString("first_name"), author.optString("last_name"))
                .filter { it.isNotBlank() }.joinToString(" ")
        }.orEmpty()

        return DiscoveredListeningResource(
            provider = "librivox",
            providerItemId = providerId,
            canonicalUrl = canonical,
            mediaUrl = mediaUrl,
            title = title,
            description = raw.optString("description"),
            language = language,
            durationMs = durationSeconds.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L,
            creator = creator,
            licenseName = "LibriVox public-domain recording; territory verification required",
            licenseUrl = "https://librivox.org/pages/public-domain/",
            rightsStatus = "review_required",
            allowedActions = emptySet(),
            attribution = "LibriVox recording; retain source attribution",
            territory = "",
            mimeType = if (zip.isNotBlank()) "application/zip" else "application/rss+xml"
        )
    }

    private fun isAllowedHttps(value: String, hosts: Set<String>): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme.equals("https", true) && uri.host?.lowercase() in hosts && uri.userInfo == null
    }.getOrDefault(false)
}

