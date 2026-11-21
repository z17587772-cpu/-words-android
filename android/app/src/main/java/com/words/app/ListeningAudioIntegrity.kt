package com.words.app

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Verifies package audio before it can replace a previously installed file.
 * The temporary file is created beside the destination so the final move
 * stays on the same filesystem.
 */
object ListeningAudioIntegrity {
    fun resolveUnderRoot(root: File, relativePath: String): File {
        require(relativePath.isNotBlank()) { "Audio path must not be blank" }
        val canonicalRoot = root.canonicalFile
        val destination = File(canonicalRoot, relativePath).canonicalFile
        val rootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        require(destination.path.startsWith(rootPrefix)) { "Audio path escapes package root" }
        return destination
    }

    fun copyAndVerify(
        source: InputStream,
        destination: File,
        expected: ListeningPackageAudio
    ): File {
        val parent = destination.absoluteFile.parentFile
            ?: throw IllegalArgumentException("Audio destination must have a parent directory")
        require(parent.exists() || parent.mkdirs()) { "Cannot create audio directory" }

        val temporary = File.createTempFile(".${destination.name}-", ".part", parent)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            source.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copied += read
                        require(copied <= expected.sizeBytes) { "Audio exceeds declared size" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }

            require(copied == expected.sizeBytes) { "Audio size does not match manifest" }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash.equals(expected.sha256, ignoreCase = true)) {
                "Audio SHA-256 does not match manifest"
            }

            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return destination
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /**
     * Installs an immutable revision asset. Existing destinations are never
     * replaced; this prevents a failed or racing import from overwriting audio
     * already referenced by a published revision.
     */
    fun copyAndVerifyNew(
        source: InputStream,
        destination: File,
        expected: ListeningPackageAudio
    ): File {
        require(!destination.exists()) { "Immutable audio destination already exists" }
        val parent = destination.absoluteFile.parentFile
            ?: throw IllegalArgumentException("Audio destination must have a parent directory")
        require(parent.exists() || parent.mkdirs()) { "Cannot create audio directory" }

        val temporary = File.createTempFile(".revision-audio-", ".part", parent)
        var installedByThisCall = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            source.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copied += read
                        require(copied <= expected.sizeBytes) { "Audio exceeds declared size" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            require(copied == expected.sizeBytes) { "Audio size does not match manifest" }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash.equals(expected.sha256, ignoreCase = true)) {
                "Audio SHA-256 does not match manifest"
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                // No REPLACE_EXISTING: a race or pre-existing destination must fail closed.
                Files.move(temporary.toPath(), destination.toPath())
            }
            installedByThisCall = true
            require(verify(destination, expected)) { "Installed audio failed post-move verification" }
            return destination
        } catch (error: Exception) {
            if (installedByThisCall) runCatching { destination.delete() }
            throw error
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun verify(file: File, expected: ListeningPackageAudio): Boolean {
        if (!file.isFile || file.length() != expected.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        return actualHash.equals(expected.sha256, ignoreCase = true)
    }
}
