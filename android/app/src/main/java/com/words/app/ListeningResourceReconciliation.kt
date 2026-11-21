package com.words.app

import android.content.Context
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
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object ListeningResourceReconciliationScheduler {
    private const val PERIODIC = "listening-resource-reconciliation"
    private const val IMMEDIATE = "listening-resource-reconciliation-now"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()
        val periodic = PeriodicWorkRequestBuilder<ListeningResourceReconcileWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic
        )
        val immediate = OneTimeWorkRequestBuilder<ListeningResourceReconcileWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE, ExistingWorkPolicy.KEEP, immediate
        )
    }
}

/** Repairs DB/file drift and removes stale temp/orphan files without touching active downloads. */
class ListeningResourceReconcileWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val helper = WordsDatabase(applicationContext)
        val repository = ListeningDiscoveryRepository(helper)
        return try {
            val root = File(applicationContext.filesDir, "listening-library")
            if (!root.exists() && !root.mkdirs()) return Result.retry()
            val rootPrefix = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
            val staleCutoff = System.currentTimeMillis() - STALE_DOWNLOAD_AGE_MS
            repository.recoverStaleDownloads(staleCutoff).forEach { id ->
                if (repository.queueDownload(id)) {
                    ListeningResourceAutomation.enqueueDownload(applicationContext, id)
                }
            }
            val referenced = linkedSetOf<String>()
            val downloaded = repository.listDownloadedLocalFiles()
            for (resource in downloaded) {
                val rawPath = resource.localFilePath
                val file = runCatching { File(rawPath).canonicalFile }.getOrNull()
                if (file == null || !file.path.startsWith(rootPrefix)) {
                    repository.markMissingLocalFile(resource.id, rawPath)
                    if (repository.queueDownload(resource.id)) {
                        ListeningResourceAutomation.enqueueDownload(applicationContext, resource.id)
                    }
                    continue
                }
                val valid = file.isFile && file.length() == resource.expectedSizeBytes &&
                    resource.contentSha256.matches(Regex("[a-fA-F0-9]{64}")) && sha256(file) == resource.contentSha256
                if (!valid) {
                    if (file.exists()) file.delete()
                    repository.markMissingLocalFile(resource.id, rawPath)
                    if (repository.queueDownload(resource.id)) {
                        ListeningResourceAutomation.enqueueDownload(applicationContext, resource.id)
                    }
                } else {
                    referenced.add(file.absolutePath)
                }
            }

            val now = System.currentTimeMillis()
            root.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val age = now - file.lastModified()
                when {
                    file.name.endsWith(".part") && age > STALE_PART_AGE_MS -> file.delete()
                    file.extension.equals("mp3", true) &&
                        file.canonicalPath !in referenced &&
                        age > ORPHAN_GRACE_MS -> file.delete()
                }
            }
            Result.success()
        } catch (error: Exception) {
            Result.retry()
        } finally {
            helper.close()
        }
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
        private const val STALE_PART_AGE_MS = 6L * 60L * 60L * 1000L
        private const val STALE_DOWNLOAD_AGE_MS = 45L * 60L * 1000L
        private const val ORPHAN_GRACE_MS = 24L * 60L * 60L * 1000L
    }
}
