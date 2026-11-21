package com.words.app

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class ExtractedListeningPackage(
    val manifestFile: File,
    val files: List<File>,
    val totalUncompressedBytes: Long
)

/**
 * Extracts only manifest.json and audio/* entries into a fresh private staging
 * directory. ZipInputStream writes regular files only; archive symlink metadata
 * is never materialized as filesystem links.
 */
object ListeningPackageArchive {
    fun extractSafely(source: InputStream, stagingDirectory: File): ExtractedListeningPackage {
        require(!stagingDirectory.exists() || stagingDirectory.listFiles().isNullOrEmpty()) {
            "Staging directory must be new or empty"
        }
        require(stagingDirectory.exists() || stagingDirectory.mkdirs()) {
            "Cannot create package staging directory"
        }

        val root = stagingDirectory.canonicalFile
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        val seen = mutableSetOf<String>()
        val extracted = mutableListOf<File>()
        var totalBytes = 0L
        var entryCount = 0

        try {
            ZipInputStream(BufferedInputStream(source)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    require(entryCount <= MAX_ENTRIES) { "Package contains too many archive entries" }

                    val rawName = entry.name
                    require(rawName.isNotBlank() && !rawName.startsWith("/") && !rawName.contains("\\")) {
                        "Archive entry path is unsafe"
                    }
                    require(!DRIVE_PREFIX.containsMatchIn(rawName)) { "Absolute archive paths are not allowed" }
                    val normalized = rawName.trimEnd('/')
                    val segments = normalized.split("/")
                    require(normalized.isNotBlank() &&
                        segments.none { it.isBlank() || it == "." || it == ".." }
                    ) { "Archive entry path is unsafe" }
                    require(rawName.toByteArray(Charsets.UTF_8).size <= MAX_ENTRY_PATH_BYTES &&
                        segments.all { it.toByteArray(Charsets.UTF_8).size <= MAX_PATH_SEGMENT_BYTES }
                    ) { "Archive entry path is too long" }
                    require(seen.add(normalized)) { "Duplicate archive entry: $normalized" }

                    val isManifest = normalized == MANIFEST_NAME && !entry.isDirectory
                    val isAudioFile = normalized.startsWith(AUDIO_PREFIX) && !entry.isDirectory
                    val isAudioDirectory = normalized == "audio" && entry.isDirectory
                    val isNestedAudioDirectory = normalized.startsWith(AUDIO_PREFIX) && entry.isDirectory
                    require(isManifest || isAudioFile || isAudioDirectory || isNestedAudioDirectory) {
                        "Unsupported package entry: $normalized"
                    }

                    val target = File(root, normalized).canonicalFile
                    require(target.path.startsWith(rootPrefix)) { "Archive entry escapes staging directory" }
                    if (entry.isDirectory) {
                        require(target.exists() || target.mkdirs()) { "Cannot create package directory" }
                    } else {
                        require(!target.exists()) { "Archive entry collides with an existing file" }
                        val parent = target.parentFile
                            ?: throw IllegalArgumentException("Archive entry has no parent directory")
                        require(parent.exists() || parent.mkdirs()) { "Cannot create package directory" }
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var entryBytes = 0L
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                entryBytes += read
                                totalBytes += read
                                require(entryBytes <= if (isManifest) MAX_MANIFEST_BYTES else MAX_ENTRY_BYTES) {
                                    "Package entry exceeds the file size limit"
                                }
                                require(totalBytes <= MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                    "Package exceeds the uncompressed size limit"
                                }
                                output.write(buffer, 0, read)
                            }
                            output.fd.sync()
                        }
                        extracted += target
                    }
                    zip.closeEntry()
                }
            }

            val manifest = File(root, MANIFEST_NAME)
            require(manifest.isFile) { "Package manifest.json is missing" }
            return ExtractedListeningPackage(manifest, extracted, totalBytes)
        } catch (error: Exception) {
            stagingDirectory.deleteRecursively()
            throw error
        }
    }

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
    private const val MANIFEST_NAME = "manifest.json"
    private const val AUDIO_PREFIX = "audio/"
    private const val MAX_ENTRIES = 2_048
    private const val MAX_ENTRY_PATH_BYTES = 512
    private const val MAX_PATH_SEGMENT_BYTES = 180
    private const val MAX_MANIFEST_BYTES = 1L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 1L * 1024L * 1024L * 1024L
}
