package com.words.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Base64
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
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Signed, data-only course updates. An unset endpoint or key deliberately
 * disables network publication until the publisher and trust key are deployed.
 */
object ListeningCatalogSync {
    private const val PREFS = "listening_catalog_sync"
    private const val PERIODIC_NAME = "listening-catalog-refresh"
    private const val IMMEDIATE_NAME = "listening-catalog-refresh-now"
    private const val MAX_CATALOG_BYTES = 2 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 1_100_000_000L
    private const val STORAGE_RESERVE_BYTES = 64L * 1024L * 1024L
    private val idPattern = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{1,79}")
    private val shaPattern = Pattern.compile("[a-fA-F0-9]{64}")

    data class PackageEntry(
        val id: String,
        val version: Int,
        val minimumAppVersionCode: Int,
        val url: String,
        val sizeBytes: Long,
        val sha256: String
    )

    fun schedule(context: Context) {
        if (!isConfigured()) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val periodic = PeriodicWorkRequestBuilder<ListeningCatalogRefreshWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic
        )
        val immediate = OneTimeWorkRequestBuilder<ListeningCatalogRefreshWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_NAME, ExistingWorkPolicy.KEEP, immediate
        )
        reconcileCompletedDownloads(context)
    }

    fun isConfigured(): Boolean =
        BuildConfig.LISTENING_CATALOG_URL.isNotBlank() &&
            BuildConfig.LISTENING_CATALOG_PUBLIC_KEY.isNotBlank()

    internal fun verifyEnvelope(envelopeBytes: ByteArray): ByteArray =
        verifyEnvelope(envelopeBytes, BuildConfig.LISTENING_CATALOG_PUBLIC_KEY)

    internal fun verifyEnvelope(envelopeBytes: ByteArray, encodedPublicKey: String): ByteArray {
        require(encodedPublicKey.isNotBlank()) { "Pinned catalog public key is not configured" }
        require(envelopeBytes.isNotEmpty() && envelopeBytes.size <= MAX_CATALOG_BYTES) {
            "Catalog envelope is empty or too large"
        }
        require(Build.VERSION.SDK_INT >= 28) { "Ed25519 catalog verification requires Android 9 or later" }
        val envelope = JSONObject(String(envelopeBytes, Charsets.UTF_8))
        val payload = Base64.decode(envelope.getString("payload"), Base64.DEFAULT)
        val signatureBytes = Base64.decode(envelope.getString("signature"), Base64.DEFAULT)
        require(payload.isNotEmpty() && payload.size <= MAX_CATALOG_BYTES) { "Catalog payload size is invalid" }
        require(signatureBytes.size == 64) { "Catalog Ed25519 signature length is invalid" }
        val encodedKey = Base64.decode(encodedPublicKey, Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encodedKey))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(payload)
        require(verifier.verify(signatureBytes)) { "Catalog signature verification failed" }
        return payload
    }

    internal fun parseCatalog(payload: ByteArray, catalogHost: String, appVersionCode: Int): Pair<Long, List<PackageEntry>> {
        val root = JSONObject(String(payload, Charsets.UTF_8))
        require(root.getInt("schema_version") == 1) { "Unsupported catalog schema" }
        val version = root.getLong("catalog_version")
        require(version > 0L) { "Catalog version must be positive" }
        val array = root.getJSONArray("packages")
        require(array.length() <= 500) { "Catalog contains too many package entries" }
        val entries = (0 until array.length()).mapNotNull { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("package_id")
            require(idPattern.matcher(id).matches()) { "Invalid package ID" }
            val packageVersion = item.getInt("package_version")
            val minimumAppVersion = item.getInt("minimum_app_version_code")
            val url = item.getString("url")
            val size = item.getLong("size_bytes")
            val digest = item.getString("sha256")
            val uri = Uri.parse(url)
            require(packageVersion > 0 && minimumAppVersion > 0) { "Invalid package version" }
            require(uri.scheme.equals("https", true) && uri.host.equals(catalogHost, true) &&
                uri.userInfo == null && uri.port in listOf(-1, 443)
            ) { "Package URL must use the catalog HTTPS host" }
            require(size in 1L..MAX_ARCHIVE_BYTES) { "Package archive size is invalid" }
            require(shaPattern.matcher(digest).matches()) { "Package SHA-256 is invalid" }
            if (minimumAppVersion > appVersionCode) return@mapNotNull null
            PackageEntry(id, packageVersion, minimumAppVersion, url, size, digest.lowercase())
        }
        require(entries.map { it.id }.toSet().size == entries.size) {
            "Catalog contains more than one version for a package ID"
        }
        return version to entries
    }

    internal fun enqueuePackage(context: Context, entry: PackageEntry, catalogHost: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installedVersion = prefs.getInt("installed_${entry.id}", 0)
        if (installedVersion >= entry.version) return
        if (prefs.all.values.filterIsInstance<String>().any { value ->
                runCatching { JSONObject(value).optString("package_id") == entry.id }.getOrDefault(false)
            }) return

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw java.io.IOException("App-specific download storage is unavailable")
        require(downloadDirectory.usableSpace >= entry.sizeBytes + STORAGE_RESERVE_BYTES) {
            "Insufficient free space for listening package"
        }
        val archive = File(downloadDirectory, "${entry.id}-${entry.version}.zip")
        if (archive.exists()) archive.delete()
        val request = DownloadManager.Request(Uri.parse(entry.url))
            .setTitle("Words 听力课程")
            .setDescription("正在下载新听力课程")
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, archive.name
            )
        val downloadId = downloadManager.enqueue(request)
        val record = JSONObject().apply {
            put("package_id", entry.id)
            put("package_version", entry.version)
            put("minimum_app_version_code", entry.minimumAppVersionCode)
            put("url", entry.url)
            put("size_bytes", entry.sizeBytes)
            put("sha256", entry.sha256)
            put("file_path", archive.absolutePath)
            put("catalog_host", catalogHost)
        }
        prefs.edit().putString("download_$downloadId", record.toString()).apply()
        // Close the tiny race where DownloadManager completes before the
        // completion broadcast receiver observes the persisted record.
        reconcileCompletedDownloads(context)
    }

    internal fun reconcileCompletedDownloads(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith("download_") || value !is String) return@forEach
            val id = key.removePrefix("download_").toLongOrNull() ?: return@forEach
            val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: run {
                deleteDownloadArchive(context, id)
                removeDownloadRecord(context, id)
                return@forEach
            }
            cursor.use {
                if (!it.moveToFirst()) {
                    deleteDownloadArchive(context, id)
                    removeDownloadRecord(context, id)
                } else {
                    when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> enqueueInstall(context, id)
                        DownloadManager.STATUS_FAILED -> {
                            deleteDownloadArchive(context, id)
                            manager.remove(id)
                            removeDownloadRecord(context, id)
                        }
                    }
                }
            }
        }
    }

    internal fun enqueueInstall(context: Context, downloadId: Long) {
        val request = OneTimeWorkRequestBuilder<ListeningPackageInstallWorker>()
            .setInputData(androidx.work.workDataOf("download_id" to downloadId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "listening-package-install-$downloadId", ExistingWorkPolicy.KEEP, request
        )
    }

    internal fun getDownloadRecord(context: Context, id: Long): JSONObject? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("download_$id", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    internal fun removeDownloadRecord(context: Context, id: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("download_$id").apply()
    }

    internal fun deleteDownloadArchive(context: Context, id: Long) {
        getDownloadRecord(context, id)?.optString("file_path")?.takeIf { it.isNotBlank() }
            ?.let { path -> File(path).delete() }
    }

    internal fun markInstalled(context: Context, id: String, version: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt("installed_$id", version).apply()
    }

    internal fun lastCatalogVersion(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("catalog_version", 0L)

    internal fun saveCatalogVersion(context: Context, version: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("catalog_version", version).apply()
    }

    internal fun fetchCatalog(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("Catalog request returned HTTP ${connection.responseCode}")
            }
            val length = connection.contentLengthLong
            require(length < 0L || length <= MAX_CATALOG_BYTES) { "Catalog response is too large" }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    require(output.size() <= MAX_CATALOG_BYTES) { "Catalog response is too large" }
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}

class ListeningCatalogRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!ListeningCatalogSync.isConfigured()) return Result.success()
        return try {
            val endpoint = Uri.parse(BuildConfig.LISTENING_CATALOG_URL)
            require(endpoint.scheme.equals("https", true) && !endpoint.host.isNullOrBlank() &&
                endpoint.userInfo == null && endpoint.port in listOf(-1, 443)
            ) { "Catalog endpoint must be HTTPS" }
            val envelope = ListeningCatalogSync.fetchCatalog(BuildConfig.LISTENING_CATALOG_URL)
            val payload = ListeningCatalogSync.verifyEnvelope(envelope)
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= 33) {
                applicationContext.packageManager.getPackageInfo(applicationContext.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
            }
            @Suppress("DEPRECATION")
            val appVersionCode = if (android.os.Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode.toInt()
                else packageInfo.versionCode
            val (catalogVersion, entries) = ListeningCatalogSync.parseCatalog(payload, endpoint.host!!, appVersionCode)
            require(catalogVersion >= ListeningCatalogSync.lastCatalogVersion(applicationContext)) {
                "Catalog rollback rejected"
            }
            entries.forEach { ListeningCatalogSync.enqueuePackage(applicationContext, it, endpoint.host!!) }
            ListeningCatalogSync.saveCatalogVersion(applicationContext, catalogVersion)
            Result.success()
        } catch (error: java.io.IOException) {
            Result.retry()
        } catch (error: Exception) {
            android.util.Log.e("ListeningCatalogSync", "Catalog refresh rejected", error)
            Result.failure()
        }
    }
}

class ListeningPackageInstallWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val downloadId = inputData.getLong("download_id", -1L)
        if (downloadId <= 0L) return Result.failure()
        val record = ListeningCatalogSync.getDownloadRecord(applicationContext, downloadId) ?: return Result.success()
        val manager = applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return Result.retry()
        val status = cursor.use {
            if (!it.moveToFirst()) return Result.retry()
            it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
        if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING ||
            status == DownloadManager.STATUS_PAUSED
        ) return Result.retry()
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            ListeningCatalogSync.removeDownloadRecord(applicationContext, downloadId)
            return Result.failure()
        }

        val archive = File(record.getString("file_path"))
        return try {
            require(archive.isFile && archive.length() == record.getLong("size_bytes")) {
                "Downloaded package size mismatch"
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            FileInputStream(archive).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(record.getString("sha256"), true)) { "Downloaded package digest mismatch" }

            val manifest = readManifestFromArchive(archive)
            val packageData = ListeningContentPackageValidator.validate(
                manifest, record.getInt("minimum_app_version_code").let { required ->
                    val current = currentAppVersionCode(applicationContext)
                    require(current >= required) { "Package requires a newer app version" }
                    current
                }
            )
            require(packageData.packageId == record.getString("package_id") &&
                packageData.packageVersion == record.getInt("package_version")
            ) { "Signed catalog entry does not match package manifest" }

            val root = File(applicationContext.filesDir, "listening-content")
            val importer = ListeningContentPackageImporter(
                WordsDatabase(applicationContext), root,
                File(applicationContext.filesDir, "listening-import-staging"),
                currentAppVersionCode(applicationContext)
            )
            val result = importer.importArchive(archive)
            require(result.packageId == record.getString("package_id") &&
                result.packageVersion == record.getInt("package_version")
            ) { "Installed package identity mismatch" }
            ListeningCatalogSync.markInstalled(applicationContext, result.packageId, result.packageVersion)
            ListeningCatalogSync.removeDownloadRecord(applicationContext, downloadId)
            archive.delete()
            manager.remove(downloadId)
            Result.success()
        } catch (error: java.io.IOException) {
            android.util.Log.e("ListeningCatalogSync", "Package install will retry", error)
            Result.retry()
        } catch (error: Exception) {
            android.util.Log.e("ListeningCatalogSync", "Package install rejected", error)
            ListeningCatalogSync.removeDownloadRecord(applicationContext, downloadId)
            archive.delete()
            manager.remove(downloadId)
            Result.failure()
        }
    }

    private fun readManifestFromArchive(archive: File): String {
        ZipFile(archive).use { zip ->
            var manifestEntry: ZipEntry? = null
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name == "manifest.json") {
                    require(manifestEntry == null) { "Package contains duplicate manifest.json entries" }
                    manifestEntry = entry
                }
            }
            val entry = requireNotNull(manifestEntry) { "Package manifest.json is missing" }
            require(!entry.isDirectory && entry.size in 1L..(1L * 1024L * 1024L)) {
                "Package manifest size is invalid"
            }
            return zip.getInputStream(entry).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    require(output.size() <= 1L * 1024L * 1024L) {
                        "Package manifest exceeds the supported size"
                    }
                }
                output.toString(Charsets.UTF_8.name())
            }
        }
    }

    private fun currentAppVersionCode(context: Context): Int {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }
}

class ListeningDownloadCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id > 0L && ListeningCatalogSync.getDownloadRecord(context, id) != null) {
            ListeningCatalogSync.enqueueInstall(context, id)
        }
    }
}
