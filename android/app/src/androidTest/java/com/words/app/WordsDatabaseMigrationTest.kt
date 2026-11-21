package com.words.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WordsDatabaseMigrationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun upgradingV5ToV13PreservesVocabularyAndLearningHistory() {
        context.deleteDatabase(DATABASE_NAME)
        createVersion5Fixture()

        val helper = WordsDatabase(context)
        val upgraded = helper.writableDatabase

        assertEquals(13, upgraded.version)
        assertEquals(1, scalar(upgraded, "SELECT COUNT(*) FROM books WHERE id='gaokao_core'"))
        assertEquals(1, scalar(upgraded, "SELECT COUNT(*) FROM words WHERE id=101 AND favorite=1 AND word='abandon'"))
        assertEquals(4, scalar(upgraded, "SELECT mastery FROM reviews WHERE word_id=101"))
        assertEquals(1, scalar(upgraded, "SELECT COUNT(*) FROM history WHERE word_id=101 AND rated_at=1700000000000"))
        assertEquals(1, scalar(upgraded, "SELECT COUNT(*) FROM word_detail_cache WHERE word='abandon'"))
        assertEquals(1, scalar(upgraded, "SELECT COUNT(*) FROM example_translation_cache WHERE sentence='I abandon the plan.'"))

        assertTrue(tableExists(upgraded, "listening_episodes"))
        assertTrue(tableExists(upgraded, "listening_attempts"))
        assertTrue(tableExists(upgraded, "listening_playback"))
        assertTrue(columnExists(upgraded, "listening_playback", "audio_revision"))
        assertTrue(tableExists(upgraded, "listening_episode_revisions"))
        assertTrue(tableExists(upgraded, "listening_revision_sentences"))
        assertTrue(tableExists(upgraded, "listening_revision_questions"))
        assertTrue(tableExists(upgraded, "listening_revision_choices"))
        assertTrue(tableExists(upgraded, "listening_content_packages"))
        assertTrue(tableExists(upgraded, "listening_package_episodes"))
        assertTrue(tableExists(upgraded, "listening_discovered_resources"))
        assertTrue(tableExists(upgraded, "listening_provider_state"))
        assertTrue(tableExists(upgraded, "listening_discovered_playback"))
        assertTrue(columnExists(upgraded, "listening_episodes", "current_revision_id"))
        assertTrue(columnExists(upgraded, "listening_attempts", "content_revision_id"))
        assertTrue(columnExists(upgraded, "listening_episode_revisions", "title"))
        assertTrue(columnExists(upgraded, "listening_episode_revisions", "audio_url"))
        assertTrue(columnExists(upgraded, "listening_daily_assignments", "content_revision_id"))
        upgraded.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            assertEquals("No foreign-key violations after migration", false, cursor.moveToFirst())
        }

        helper.close()
    }

    @Test
    fun upgradingV6ToV11PreservesAudioRevisionAndSavedPlayback() {
        context.deleteDatabase(DATABASE_NAME)
        createVersion5Fixture()
        val legacy = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null)
        legacy.execSQL("CREATE TABLE listening_episodes (id TEXT PRIMARY KEY)")
        legacy.execSQL("""
            CREATE TABLE listening_playback (
                episode_id TEXT PRIMARY KEY,
                position_ms INTEGER NOT NULL DEFAULT 0,
                playback_speed REAL NOT NULL DEFAULT 1.0,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(episode_id) REFERENCES listening_episodes(id) ON DELETE CASCADE
            )
        """.trimIndent())
        legacy.execSQL("INSERT INTO listening_episodes(id) VALUES('legacy-episode')")
        legacy.execSQL("INSERT INTO listening_playback(episode_id,position_ms,playback_speed,updated_at) VALUES('legacy-episode',12345,1.25,1700000000000)")
        legacy.version = 6
        legacy.close()

        val helper = WordsDatabase(context)
        val upgraded = helper.writableDatabase
        assertEquals(11, upgraded.version)
        assertEquals(12_345, scalar(upgraded, "SELECT position_ms FROM listening_playback WHERE episode_id='legacy-episode'"))
        assertEquals(1.25f, upgraded.rawQuery("SELECT playback_speed FROM listening_playback WHERE episode_id='legacy-episode'", null).use {
            assertTrue(it.moveToFirst())
            it.getFloat(0)
        }, 0.001f)
        assertEquals("", upgraded.rawQuery("SELECT audio_revision FROM listening_playback WHERE episode_id='legacy-episode'", null).use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        })
        helper.close()
    }

    @Test
    fun playbackStateRoundTripsAndDemoNeverBecomesDailyAssignment() {
        context.deleteDatabase(DATABASE_NAME)
        val helper = WordsDatabase(context)
        val repository = ListeningRepository(helper)
        repository.ensureDemoSeeded()

        val episodeId = ListeningRepository.DEMO_EPISODE_ID
        val db = helper.writableDatabase
        db.insertOrThrow("listening_daily_assignments", null, android.content.ContentValues().apply {
            put("assignment_date", "2026-11-20")
            put("episode_id", episodeId)
            put("created_at", 1_795_132_800_000L)
        })
        assertEquals(null, repository.assignDailyEpisode("2026-11-20", 1_795_132_800_000L))
        assertEquals(
            0,
            scalar(db, "SELECT COUNT(*) FROM listening_daily_assignments WHERE episode_id='${episodeId}'")
        )
        assertEquals(null, repository.getPlayback(episodeId))
        repository.savePlayback(episodeId, -500L, Float.NaN, "rev-1")
        val first = repository.getPlayback(episodeId)
        assertEquals(0L, first?.positionMs)
        assertEquals(1.0f, first?.speed ?: 0f, 0.001f)

        repository.savePlayback(episodeId, 42_500L, 1.5f, "rev-1")
        val restored = repository.getPlayback(episodeId, "rev-1")
        assertEquals(episodeId, restored?.episodeId)
        assertEquals(42_500L, restored?.positionMs)
        assertEquals(1.5f, restored?.speed ?: 0f, 0.001f)
        assertEquals(0L, repository.getPlayback(episodeId, "rev-2")?.positionMs)
        assertEquals(1.5f, repository.getPlayback(episodeId, "rev-2")?.speed ?: 0f, 0.001f)

        assertEquals(
            0,
            scalar(helper.readableDatabase, "SELECT COUNT(*) FROM listening_daily_assignments WHERE episode_id='$episodeId'")
        )
        helper.close()
    }

    @Test
    fun completedReviewRecoversSelectedAndCorrectChoicesWithoutLeakingKeysToOptions() {
        context.deleteDatabase(DATABASE_NAME)
        val helper = WordsDatabase(context)
        val repository = ListeningRepository(helper)
        repository.ensureDemoSeeded()
        val episodeId = ListeningRepository.DEMO_EPISODE_ID
        val question = repository.getQuestions(episodeId).first()
        val correctId = repository.getCorrectChoiceId(question.id)!!
        val wrongOption = question.options.first { it.id != correctId }
        val attemptId = repository.startAttempt(episodeId)

        assertEquals(2, question.options.size)
        assertFalse(repository.recordAnswer(attemptId, question.id, wrongOption.id))
        assertFalse(repository.recordAnswer(attemptId, question.id, correctId))
        assertTrue(repository.getAttemptAnswerReviews(attemptId).isEmpty())
        val anotherQuestion = repository.getQuestions(episodeId)[1]
        try {
            repository.recordAnswer(attemptId, anotherQuestion.id, wrongOption.id)
            throw AssertionError("A choice from another question was accepted")
        } catch (_: IllegalArgumentException) {
            // Expected question/attempt ownership validation.
        }
        try {
            repository.finishAttempt(attemptId)
            throw AssertionError("An incomplete attempt was completed")
        } catch (_: IllegalArgumentException) {
            // Every question must be answered before completion.
        }
        repository.getQuestions(episodeId).drop(1).forEach { unanswered ->
            val correctChoice = repository.getCorrectChoiceId(unanswered.id)!!
            assertTrue(repository.recordAnswer(attemptId, unanswered.id, correctChoice))
        }
        assertEquals(0.8f, repository.finishAttempt(attemptId), 0.001f)
        assertEquals(0.8f, repository.finishAttempt(attemptId), 0.001f)
        assertEquals(1, repository.completedAttemptCount())

        val review = repository.getAttemptAnswerReviews(attemptId).first { it.questionId == question.id }
        assertEquals(wrongOption.id, review.selectedChoiceId)
        assertEquals(wrongOption.content, review.selectedContent)
        assertEquals(correctId, review.correctChoiceId)
        assertTrue(review.correctContent.isNotBlank())
        assertFalse(review.isCorrect)
        helper.writableDatabase.insertOrThrow(
            "listening_questions",
            null,
            android.content.ContentValues().apply {
                put("id", "post-completion-question")
                put("episode_id", episodeId)
                put("ordinal", 6)
                put("question_type", "detail")
                put("prompt", "A question added after completion")
                put("explanation", "Historical attempts must keep their original score.")
            }
        )
        assertEquals(0.8f, repository.finishAttempt(attemptId), 0.001f)
        helper.close()
    }

    @Test
    fun contentPackageValidatorAcceptsCompleteManifestAndRejectsUnsafeReferences() {
        val valid = ListeningContentPackageValidator.validate(validPackageManifest(), 22)
        assertEquals("words-listening-core", valid.packageId)
        assertEquals(1, valid.episodes.size)
        assertEquals(3, valid.episodes.single().questions.size)
        assertTrue(valid.episodes.single().questions.all { question ->
            question.choices.count { choice -> choice.isCorrect } == 1
        })

        assertInvalid(validPackageManifest().replace("audio/lesson-01.mp3", "../outside.mp3"))
        assertInvalid(validPackageManifest().replace("audio/lesson-01.mp3", "images/lesson-01.mp3"))
        assertInvalid(validPackageManifest().replace("audio/mpeg", "audio/unknown-format"))
        assertInvalid(validPackageManifest().replace("a".repeat(64), "not-a-checksum"))
        assertInvalid(validPackageManifest().replace("\"evidence_sentence_id\": \"sentence-01\"", "\"evidence_sentence_id\": \"missing-sentence\""))
        assertInvalid(validPackageManifest().replace("\"in_app_playback\"", "\"streaming_only\""))
        assertInvalid(validPackageManifest().replace("\"offline_download\"", "\"streaming_only\""))
        assertInvalid(validPackageManifest().replace("\"schema_version\": 1", "\"schema_version\": 99"))
    }

    @Test
    fun audioInstallVerifiesChecksumAndKeepsPreviousFileOnFailure() {
        val root = java.io.File(context.cacheDir, "listening-integrity-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        val destination = java.io.File(root, "lesson.mp3")
        destination.writeText("previous-good-audio")
        val bytes = "hello world".toByteArray()
        val descriptor = ListeningPackageAudio(
            path = "audio/lesson.mp3",
            sizeBytes = bytes.size.toLong(),
            sha256 = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            mimeType = "audio/mpeg",
            language = "en-US"
        )

        try {
            try {
                ListeningAudioIntegrity.copyAndVerify(
                    java.io.ByteArrayInputStream("jello world".toByteArray()),
                    destination,
                    descriptor
                )
                throw AssertionError("Corrupt audio was installed")
            } catch (_: IllegalArgumentException) {
                assertEquals("previous-good-audio", destination.readText())
            }

            ListeningAudioIntegrity.copyAndVerify(
                java.io.ByteArrayInputStream(bytes),
                destination,
                descriptor
            )
            assertTrue(ListeningAudioIntegrity.verify(destination, descriptor))
            assertEquals("hello world", destination.readText())

            val immutableDestination = java.io.File(root, "revision-audio.mp3")
            ListeningAudioIntegrity.copyAndVerifyNew(
                java.io.ByteArrayInputStream(bytes),
                immutableDestination,
                descriptor
            )
            try {
                ListeningAudioIntegrity.copyAndVerifyNew(
                    java.io.ByteArrayInputStream("jello world".toByteArray()),
                    immutableDestination,
                    descriptor
                )
                throw AssertionError("An immutable revision audio file was overwritten")
            } catch (_: IllegalArgumentException) {
                assertEquals("hello world", immutableDestination.readText())
            }

            try {
                ListeningAudioIntegrity.resolveUnderRoot(root, "../escape.mp3")
                throw AssertionError("Path traversal was accepted")
            } catch (_: IllegalArgumentException) {
                // Expected path validation failure.
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun packageArchiveExtractsOnlySafeEntriesAndCleansFailedStaging() {
        val validRoot = java.io.File(context.cacheDir, "listening-archive-valid-${System.nanoTime()}")
        val archive = createZip(
            "manifest.json" to "{}".toByteArray(),
            "audio/lesson.mp3" to "audio-bytes".toByteArray()
        )
        val extracted = ListeningPackageArchive.extractSafely(
            java.io.ByteArrayInputStream(archive),
            validRoot
        )
        assertTrue(extracted.manifestFile.isFile)
        assertEquals("{}", extracted.manifestFile.readText())
        assertEquals(2, extracted.files.size)
        assertEquals("audio-bytes", java.io.File(validRoot, "audio/lesson.mp3").readText())
        validRoot.deleteRecursively()

        val maliciousRoot = java.io.File(context.cacheDir, "listening-archive-bad-${System.nanoTime()}")
        val outside = java.io.File(context.cacheDir, "escape-${System.nanoTime()}.txt")
        try {
            ListeningPackageArchive.extractSafely(
                java.io.ByteArrayInputStream(createZip("../${outside.name}" to "nope".toByteArray())),
                maliciousRoot
            )
            throw AssertionError("Path traversal archive was accepted")
        } catch (_: IllegalArgumentException) {
            assertFalse(maliciousRoot.exists())
            assertFalse(outside.exists())
        }

        val longPathRoot = java.io.File(context.cacheDir, "listening-archive-long-${System.nanoTime()}")
        try {
            ListeningPackageArchive.extractSafely(
                java.io.ByteArrayInputStream(
                    createZip("manifest.json" to "{}".toByteArray(), "audio/${"x".repeat(181)}.mp3" to byteArrayOf(1))
                ),
                longPathRoot
            )
            throw AssertionError("Overlong archive path was accepted")
        } catch (_: IllegalArgumentException) {
            assertFalse(longPathRoot.exists())
        }
    }

    @Test
    fun packageArchivePreservesConfiguredFreeSpaceReserve() {
        val staging = java.io.File(context.cacheDir, "listening-archive-space-${System.nanoTime()}")
        try {
            ListeningPackageArchive.extractSafely(
                java.io.ByteArrayInputStream(createZip("manifest.json" to "{}".toByteArray())),
                staging,
                Long.MAX_VALUE
            )
            throw AssertionError("Archive extraction ignored the configured free-space reserve")
        } catch (_: IllegalArgumentException) {
            assertFalse(staging.exists())
        }
    }

    @Test
    fun dailyPlannerRejectsStreamingOnlyLicense() {
        context.deleteDatabase(DATABASE_NAME)
        val helper = WordsDatabase(context)
        val repository = ListeningRepository(helper)
        val db = helper.writableDatabase
        insertApprovedLesson(db, "stream-only-lesson", "L4", "daily", "American")
        db.update(
            "listening_licenses",
            android.content.ContentValues().apply { put("allowed_actions", "in_app_playback") },
            "id=?",
            arrayOf("license-stream-only-lesson")
        )

        assertEquals(null, repository.assignDailyEpisode("2026-11-21", 1_795_300_000_000L))
        assertEquals(
            0,
            scalar(db, "SELECT COUNT(*) FROM listening_daily_assignments WHERE episode_id='stream-only-lesson'")
        )
        helper.close()
    }

    @Test
    fun packageImporterIsIdempotentVersionSafeAndPreservesAttemptRevision() {
        context.deleteDatabase(DATABASE_NAME)
        val helper = WordsDatabase(context)
        val repository = ListeningRepository(helper)
        val contentRoot = java.io.File(context.filesDir, "listening-import-test-${System.nanoTime()}")
        val stagingRoot = java.io.File(context.cacheDir, "listening-import-stage-${System.nanoTime()}")
        val archiveFile = java.io.File(context.cacheDir, "listening-package-v1-${System.nanoTime()}.zip")
        val importer = ListeningContentPackageImporter(helper, contentRoot, stagingRoot, 22)
        val nowMs = 1_795_300_000_000L
        val audioV1 = ByteArray(1024) { 0x2A.toByte() }
        val digestV1 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(audioV1).joinToString("") { "%02x".format(it) }
        val manifestV1 = validPackageManifest().replace("a".repeat(64), digestV1)
        archiveFile.writeBytes(createZip(
            "manifest.json" to manifestV1.toByteArray(Charsets.UTF_8),
            "audio/lesson-01.mp3" to audioV1
        ))

        try {
            val first = importer.importArchive(archiveFile, nowMs)
            assertTrue(first is ListeningPackageImportResult.Installed)

            assertEquals(1, scalar(helper.readableDatabase,
                "SELECT COUNT(*) FROM listening_revision_questions WHERE prompt='What is the main idea?'"))
            helper.writableDatabase.execSQL(
                """UPDATE listening_revision_questions SET prompt='tampered prompt'
                   WHERE revision_id=(SELECT current_revision_id FROM listening_episodes WHERE id='lesson-01')
                     AND ordinal=1"""
            )
            try {
                importer.importArchive(archiveFile, nowMs)
                throw AssertionError("Idempotent import accepted a tampered immutable question snapshot")
            } catch (_: IllegalArgumentException) {
                // A matching archive digest is not enough when installed rows have changed.
            }
            helper.writableDatabase.execSQL(
                """UPDATE listening_revision_questions SET prompt='What is the main idea?'
                   WHERE revision_id=(SELECT current_revision_id FROM listening_episodes WHERE id='lesson-01')
                     AND ordinal=1"""
            )
            assertTrue(importer.importArchive(archiveFile, nowMs) is ListeningPackageImportResult.AlreadyInstalled)

            val episodeId = "lesson-01"
            assertEquals(1, scalar(helper.readableDatabase,
                "SELECT COUNT(*) FROM listening_package_episodes WHERE episode_id='lesson-01' AND is_active=1"))
            assertEquals(3, scalar(helper.readableDatabase,
                "SELECT COUNT(*) FROM listening_revision_questions WHERE revision_id=(SELECT current_revision_id FROM listening_episodes WHERE id='lesson-01')"))

            val assignmentDate = java.time.LocalDate.now().toString()
            assertEquals(episodeId, repository.assignDailyEpisode(assignmentDate, nowMs))
            assertEquals("A Small Change", repository.getEpisodeForDailyAssignment(assignmentDate, episodeId)?.title)
            val attemptId = repository.startAttempt(episodeId)
            assertEquals("blind", repository.getAttemptProgress(attemptId)?.stage)
            repository.updateAttemptStage(attemptId, "quiz")
            assertEquals("quiz", repository.getAttemptProgress(attemptId)?.stage)
            val oldEpisode = repository.getEpisodeForAttempt(attemptId)!!
            val oldAudioUri = oldEpisode.audioUrl!!
            val oldAudioFile = java.io.File(java.net.URI(oldAudioUri))
            assertTrue(oldAudioFile.isFile)
            val originalQuestions = repository.getQuestionsForAttempt(attemptId)
            assertEquals(3, originalQuestions.size)
            originalQuestions.forEach { question ->
                val correctChoice = repository.getCorrectChoiceId(attemptId, question.id)!!
                assertTrue(repository.recordAnswer(attemptId, question.id, correctChoice))
            }
            assertEquals(3, repository.getAttemptCorrectCount(attemptId))
            assertTrue(repository.getAttemptAnswer(attemptId, originalQuestions.first().id)?.isCorrect == true)
            assertEquals(1.0f, repository.finishAttempt(attemptId), 0.001f)
            assertTrue(repository.getAttemptProgress(attemptId)?.isCompleted == true)

            val audioV2 = ByteArray(1024) { 0x3B.toByte() }
            val digestV2 = java.security.MessageDigest.getInstance("SHA-256")
                .digest(audioV2).joinToString("") { "%02x".format(it) }
            val manifestV2 = manifestV1
                .replace("\"package_version\": 1", "\"package_version\": 2")
                .replace("2026-11-core-01", "2026-11-core-02")
                .replace("A Small Change", "A Better Small Change")
                .replace(digestV1, digestV2)
            val archiveV2 = java.io.File(context.cacheDir, "listening-package-v2-${System.nanoTime()}.zip")
            archiveV2.writeBytes(createZip(
                "manifest.json" to manifestV2.toByteArray(Charsets.UTF_8),
                "audio/lesson-01.mp3" to audioV2
            ))
            try {
                assertTrue(importer.importArchive(archiveV2, nowMs) is ListeningPackageImportResult.Installed)
                assertEquals("A Small Change", repository.getEpisodeForAttempt(attemptId)?.title)
                assertEquals("A Small Change", repository.getEpisodeForDailyAssignment(assignmentDate, episodeId)?.title)
                val currentEpisode = repository.getEpisode(episodeId)!!
                assertEquals("A Better Small Change", currentEpisode.title)
                assertTrue(repository.isKnownPlaybackRevision(
                    episodeId, oldAudioUri, oldEpisode.playbackRevision(oldAudioUri), 1_000L
                ))
                val currentAudioUri = currentEpisode.audioUrl!!
                assertTrue(repository.isKnownPlaybackRevision(
                    episodeId, currentAudioUri, currentEpisode.playbackRevision(currentAudioUri), 1_000L
                ))
                assertFalse(repository.isKnownPlaybackRevision(
                    episodeId, "file:///not-a-published-revision.mp3", "wrong-revision", 1_000L
                ))
                assertEquals(3, repository.getAttemptAnswerReviews(attemptId).size)
                assertTrue(repository.getAttemptAnswerReviews(attemptId).all { it.isCorrect })
                assertEquals(1.0f, repository.finishAttempt(attemptId), 0.001f)
                assertTrue("Historical revision audio must remain available", oldAudioFile.isFile)
                assertEquals(2, scalar(helper.readableDatabase,
                    "SELECT package_version FROM listening_content_packages WHERE package_id='words-listening-core'"))
                val orphan = java.io.File(contentRoot, "orphan/rev_${"f".repeat(40)}.mp3")
                assertTrue(orphan.parentFile!!.mkdirs())
                orphan.writeText("unreferenced")
                val unrelatedFile = java.io.File(contentRoot, "keep-me.txt")
                unrelatedFile.writeText("not an importer revision")
                assertEquals(1, importer.recoverOrphanedAudioFiles())
                assertFalse(orphan.exists())
                assertTrue(unrelatedFile.isFile)
                assertTrue(oldAudioFile.isFile)
                helper.writableDatabase.delete(
                    "listening_daily_assignments",
                    "assignment_date=?",
                    arrayOf(assignmentDate)
                )
                val newAttemptId = repository.startAttempt(episodeId)
                assertEquals(newAttemptId, repository.getLatestIncompleteAttempt()?.id)
                assertEquals("A Better Small Change", repository.getEpisodeForAttempt(newAttemptId)?.title)
                repository.abandonAttempt(newAttemptId)
                assertEquals("abandoned", repository.getAttemptProgress(newAttemptId)?.stage)
                assertTrue(repository.getLatestIncompleteAttempt() == null)

                val conflictingManifest = manifestV2.replace("A Better Small Change", "Conflicting same-version content")
                val conflictingArchive = java.io.File(context.cacheDir, "listening-package-conflict-${System.nanoTime()}.zip")
                conflictingArchive.writeBytes(createZip(
                    "manifest.json" to conflictingManifest.toByteArray(Charsets.UTF_8),
                    "audio/lesson-01.mp3" to audioV2
                ))
                try {
                    try {
                        importer.importArchive(conflictingArchive, nowMs)
                        throw AssertionError("Same-version package with a different digest was accepted")
                    } catch (_: IllegalArgumentException) {
                        assertEquals(2, scalar(helper.readableDatabase,
                            "SELECT package_version FROM listening_content_packages WHERE package_id='words-listening-core'"))
                    }
                } finally {
                    conflictingArchive.delete()
                }

                try {
                    importer.importArchive(archiveFile, nowMs)
                    throw AssertionError("Package downgrade was accepted")
                } catch (_: IllegalArgumentException) {
                    assertEquals("A Better Small Change", repository.getEpisode(episodeId)?.title)
                }
            } finally {
                archiveV2.delete()
            }
        } finally {
            archiveFile.delete()
            contentRoot.deleteRecursively()
            stagingRoot.deleteRecursively()
            helper.close()
        }
    }

    @Test
    fun packageImporterCannotPromoteTheSystemTtsDemoToFormalContent() {
        context.deleteDatabase(DATABASE_NAME)
        val helper = WordsDatabase(context)
        val repository = ListeningRepository(helper)
        repository.ensureDemoSeeded()
        val root = java.io.File(context.cacheDir, "listening-demo-import-${System.nanoTime()}")
        val archive = java.io.File(context.cacheDir, "listening-demo-package-${System.nanoTime()}.zip")
        val audio = ByteArray(1024) { 0x2A.toByte() }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(audio).joinToString("") { "%02x".format(it) }
        val manifest = validPackageManifest()
            .replace("\"id\": \"lesson-01\"", "\"id\": \"${ListeningRepository.DEMO_EPISODE_ID}\"")
            .replace("a".repeat(64), digest)
        archive.writeBytes(createZip(
            "manifest.json" to manifest.toByteArray(Charsets.UTF_8),
            "audio/lesson-01.mp3" to audio
        ))
        val importer = ListeningContentPackageImporter(
            helper,
            java.io.File(root, "content"),
            java.io.File(root, "staging"),
            22
        )
        try {
            try {
                importer.importArchive(archive, 1_795_300_000_000L)
                throw AssertionError("Package import promoted the demo episode")
            } catch (_: IllegalArgumentException) {
                assertEquals(1, scalar(helper.readableDatabase,
                    "SELECT is_demo FROM listening_episodes WHERE id='${ListeningRepository.DEMO_EPISODE_ID}'"))
                assertEquals(0, scalar(helper.readableDatabase,
                    "SELECT COUNT(*) FROM listening_content_packages"))
            }
        } finally {
            archive.delete()
            root.deleteRecursively()
            helper.close()
        }
    }

    @Test
    fun dailyPlannerPrefersAdvancedContentThenAvoidsRecentRepeats() {
        context.deleteDatabase(DATABASE_NAME)
        val helper = WordsDatabase(context)
        val repository = ListeningRepository(helper)
        repository.ensureDemoSeeded()
        val db = helper.writableDatabase

        insertApprovedLesson(db, "lesson-basic", "L2", "daily", "American")
        insertApprovedLesson(db, "lesson-advanced", "L4", "science", "British")

        val firstDay = repository.assignDailyEpisode("2026-11-21", 1_795_219_200_000L)
        assertEquals("lesson-advanced", firstDay)
        assertEquals(firstDay, repository.assignDailyEpisode("2026-11-21", 1_795_219_200_000L))
        assertTrue(db.rawQuery(
            "SELECT content_revision_id FROM listening_daily_assignments WHERE assignment_date='2026-11-21'",
            null
        ).use { it.moveToFirst() && !it.isNull(0) })

        val secondDay = repository.assignDailyEpisode("2026-11-22", 1_795_305_600_000L)
        assertEquals("lesson-basic", secondDay)
        assertEquals(
            0,
            scalar(db, "SELECT COUNT(*) FROM listening_daily_assignments WHERE episode_id='${ListeningRepository.DEMO_EPISODE_ID}'")
        )
        assertEquals(2, scalar(db, "SELECT COUNT(*) FROM listening_daily_assignments"))
        helper.close()
    }


    @Test
    fun dailyPlannerNeverRepeatsAssignedLessonsAndAddsNewLessonsImmediately() {
        context.deleteDatabase(DATABASE_NAME)
        val helper = WordsDatabase(context)
        val repository = ListeningRepository(helper)
        repository.ensureDemoSeeded()
        val db = helper.writableDatabase

        insertApprovedLesson(db, "rotation-basic", "L2", "daily", "American")
        insertApprovedLesson(db, "rotation-advanced", "L4", "science", "British")

        val dayOne = repository.assignDailyEpisode("2026-11-21", 1_795_219_200_000L)
        val dayTwo = repository.assignDailyEpisode("2026-11-22", 1_795_305_600_000L)
        assertEquals("rotation-advanced", dayOne)
        assertEquals("rotation-basic", dayTwo)

        // A backdated request must not recycle a lesson already assigned on a
        // later date; the invariant is based on all assignments, not date order.
        assertEquals(null, repository.assignDailyEpisode("2026-11-20", 1_795_132_800_000L))

        // A newly installed lesson becomes the only unseen candidate and is
        // assigned immediately, without restarting or replaying the old pass.
        insertApprovedLesson(db, "rotation-new", "L3", "campus", "Australian")
        val dayThree = repository.assignDailyEpisode("2026-11-23", 1_795_392_000_000L)
        assertEquals("rotation-new", dayThree)

        // Exhausting the unseen pool is an honest empty state, never a repeat.
        assertEquals(null, repository.assignDailyEpisode("2026-11-24", 1_795_478_400_000L))

        // A further newly published episode replenishes the new-lesson queue.
        insertApprovedLesson(db, "rotation-newer", "L4", "interview", "Irish")
        assertEquals("rotation-newer", repository.assignDailyEpisode("2026-11-25", 1_795_564_800_000L))
        assertEquals(null, repository.assignDailyEpisode("2026-11-26", 1_795_651_200_000L))

        assertEquals(4, scalar(db, "SELECT COUNT(*) FROM listening_daily_assignments"))
        assertEquals(
            0,
            scalar(db, "SELECT COUNT(*) FROM listening_daily_assignments WHERE episode_id='${ListeningRepository.DEMO_EPISODE_ID}'")
        )
        helper.close()
    }

    @Test
    fun reviewPlannerSchedulesWeakScoresSoonerThanStrongScores() {
        val day = 24L * 60L * 60L * 1000L
        val completedAt = 1_000_000L
        val candidates = listOf(
            ListeningReviewCandidate("weak", completedAt, 0.20f, 1),
            ListeningReviewCandidate("medium", completedAt, 0.55f, 2),
            ListeningReviewCandidate("good", completedAt, 0.75f, 1),
            ListeningReviewCandidate("strong", completedAt, 0.95f, 4),
            ListeningReviewCandidate("invalid", completedAt, Float.NaN, 1),
            ListeningReviewCandidate("empty-history", completedAt, 0.10f, 0)
        )

        assertEquals(
            listOf("weak"),
            ListeningReviewPlanner.due(candidates, completedAt + day).map { it.episodeId }
        )
        assertEquals(
            listOf("weak", "medium"),
            ListeningReviewPlanner.due(candidates, completedAt + 3 * day).map { it.episodeId }
        )
        assertEquals(
            listOf("weak", "medium", "good"),
            ListeningReviewPlanner.due(candidates, completedAt + 7 * day).map { it.episodeId }
        )
        assertEquals(
            listOf("weak", "medium", "good", "strong"),
            ListeningReviewPlanner.due(candidates, completedAt + 14 * day).map { it.episodeId }
        )
    }

    @Test
    fun dailyPlannerMaintainsNoRepeatInvariantAcrossYearOfWeeklyCatalogGrowth() {
        val catalog = mutableListOf<ListeningEpisode>()
        val history = mutableListOf<ListeningAssignmentHistory>()
        val assignedIds = mutableSetOf<String>()
        val categories = listOf("daily", "campus", "news", "science", "story", "interview", "exam")
        val levels = listOf("L1", "L2", "L3", "L4")
        val accents = listOf("American", "British", "Australian", "Irish")

        // Add seven genuinely new episodes every week and assign one per day.
        // 53 weekly batches provide enough unseen lessons for a 371-day run.
        for (day in 0 until 371) {
            if (day % 7 == 0) {
                repeat(7) { index ->
                    val id = "year-${day}-${index}"
                    catalog += ListeningEpisode(
                        id = id,
                        title = "Lesson $id",
                        description = "Long-run rotation regression fixture",
                        category = categories[(day / 7 + index) % categories.size],
                        level = levels[(day / 7 + index) % levels.size],
                        durationMs = 60_000L,
                        accent = accents[(day / 7 + index) % accents.size],
                        speechRate = 1.0f,
                        audioUrl = "https://example.invalid/$id.mp3",
                        isDemo = false,
                        updatedAt = day.toLong()
                    )
                }
            }

            val date = java.time.LocalDate.of(2026, 1, 1).plusDays(day.toLong()).toString()
            val selected = ListeningDailyPlanner.choose(catalog, history, date)
                ?: throw AssertionError("Expected a new lesson on day $day")
            assertTrue("Repeated new lesson on day $day: ${selected.id}", assignedIds.add(selected.id))
            history += ListeningAssignmentHistory(
                episodeId = selected.id,
                category = selected.category,
                level = selected.level,
                accent = selected.accent,
                assignmentCount = 1
            )
        }

        assertEquals(371, assignedIds.size)
        assertEquals(null, ListeningDailyPlanner.choose(
            catalog,
            history,
            java.time.LocalDate.of(2027, 1, 7).toString()
        ))
    }

    @Test
    fun dailyPlannerDoesNotTreatAnUpdatedRevisionAsANewLesson() {
        context.deleteDatabase(DATABASE_NAME)
        val helper = WordsDatabase(context)
        val repository = ListeningRepository(helper)
        val db = helper.writableDatabase
        insertApprovedLesson(db, "stable-episode-id", "L3", "science", "British")
        val original = repository.getEpisode("stable-episode-id")!!
        val updatedRevision = original.copy(
            title = "Corrected title",
            audioUrl = "https://example.invalid/corrected-audio.mp3",
            updatedAt = original.updatedAt + 1_000L
        )
        val history = listOf(
            ListeningAssignmentHistory(
                episodeId = original.id,
                category = original.category,
                level = original.level,
                accent = original.accent,
                assignmentCount = 1
            )
        )

        assertEquals(
            null,
            ListeningDailyPlanner.choose(listOf(updatedRevision), history, "2026-11-22")
        )
        helper.close()
    }


    @Test
    fun attemptReviewReadsPinnedSnapshotInsteadOfMutableCatalogRows() {
        context.deleteDatabase(DATABASE_NAME)
        val helper = WordsDatabase(context)
        val repository = ListeningRepository(helper)
        repository.ensureDemoSeeded()
        val episodeId = ListeningRepository.DEMO_EPISODE_ID
        val originalQuestions = repository.getQuestions(episodeId)
        val originalFirst = originalQuestions.first()
        val originalEpisode = repository.getEpisode(episodeId)!!
        val originalCorrectId = repository.getCorrectChoiceId(originalFirst.id)!!
        val attemptId = repository.startAttempt(episodeId)
        assertTrue(helper.readableDatabase.rawQuery(
            "SELECT content_revision_id FROM listening_attempts WHERE id=?",
            arrayOf(attemptId.toString())
        ).use { it.moveToFirst() && !it.isNull(0) })

        originalQuestions.forEach { question ->
            val correctId = repository.getCorrectChoiceId(question.id)!!
            assertTrue(repository.recordAnswer(attemptId, question.id, correctId))
        }
        assertEquals(1.0f, repository.finishAttempt(attemptId), 0.001f)

        val db = helper.writableDatabase
        db.execSQL("UPDATE listening_questions SET prompt='mutated live prompt', explanation='mutated live explanation' WHERE id=?",
            arrayOf(originalFirst.id))
        db.execSQL("UPDATE listening_episodes SET title='mutated live title', audio_url='https://example.invalid/new-audio.mp3' WHERE id=?",
            arrayOf(episodeId))
        db.execSQL("UPDATE listening_choices SET content='mutated live option', is_correct=1-is_correct WHERE question_id=?",
            arrayOf(originalFirst.id))

        val pinnedEpisode = repository.getEpisodeForAttempt(attemptId)!!
        assertEquals(originalEpisode.title, pinnedEpisode.title)
        assertEquals(originalEpisode.audioUrl, pinnedEpisode.audioUrl)
        assertEquals("mutated live title", repository.getEpisode(episodeId)?.title)
        val pinnedQuestion = repository.getQuestionsForAttempt(attemptId).first()
        assertEquals(originalFirst.prompt, pinnedQuestion.prompt)
        assertEquals(originalFirst.explanation, pinnedQuestion.explanation)
        assertEquals(originalFirst.options.map { it.content }, pinnedQuestion.options.map { it.content })
        assertEquals(originalFirst.prompt, repository.getQuestions(episodeId).first().prompt)
        assertEquals(originalCorrectId, repository.getCorrectChoiceId(attemptId, originalFirst.id))
        assertEquals(originalCorrectId, repository.getCorrectChoiceId(originalFirst.id))
        assertEquals(repository.getSentences(episodeId), repository.getSentencesForAttempt(attemptId))
        val review = repository.getAttemptAnswerReviews(attemptId).first { it.questionId == originalFirst.id }
        assertEquals(originalFirst.options.first { it.id == originalCorrectId }.content, review.correctContent)
        assertTrue(review.isCorrect)
        assertEquals(1.0f, repository.finishAttempt(attemptId), 0.001f)
        helper.close()
    }


    @Test
    fun dailyArchiveReplaysTheAssignedHistoricalRevision() {
        context.deleteDatabase(DATABASE_NAME)
        val helper = WordsDatabase(context)
        val repository = ListeningRepository(helper)
        val episodeId = "archive-revision-test"
        val db = helper.writableDatabase
        insertApprovedLesson(db, episodeId, "L2", "daily", "American")

        val originalAttempt = repository.startAttempt(episodeId)
        val originalRevision = db.rawQuery(
            "SELECT content_revision_id FROM listening_attempts WHERE id=?",
            arrayOf(originalAttempt.toString())
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) }
        val assignmentDate = "2026-10-01"
        db.insertOrThrow("listening_daily_assignments", null, android.content.ContentValues().apply {
            put("assignment_date", assignmentDate)
            put("episode_id", episodeId)
            put("content_revision_id", originalRevision)
            put("created_at", 1_798_800_000_000L)
        })
        db.execSQL("UPDATE listening_episodes SET title='Changed title' WHERE id=?", arrayOf(episodeId))

        assertEquals(episodeId, repository.getEpisodeForDailyAssignment(assignmentDate, episodeId)?.id)
        assertEquals(episodeId, repository.getEpisodeForDailyAssignment(assignmentDate, episodeId)?.let { it.id })
        val historicalAttempt = repository.startAttempt(episodeId, assignmentDate)
        val pinnedRevision = db.rawQuery(
            "SELECT content_revision_id FROM listening_attempts WHERE id=?",
            arrayOf(historicalAttempt.toString())
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) }
        assertEquals(originalRevision, pinnedRevision)
        assertEquals("archive-revision-test", repository.getEpisodeForAttempt(historicalAttempt)?.title)
        helper.close()
    }

    @Test
    fun upgradingV7ToV11BackfillsSnapshotsAndPinsExistingAttempts() {
        context.deleteDatabase(DATABASE_NAME)
        val originalHelper = WordsDatabase(context)
        val originalRepository = ListeningRepository(originalHelper)
        originalRepository.ensureDemoSeeded()
        val episodeId = ListeningRepository.DEMO_EPISODE_ID
        val originalQuestions = originalRepository.getQuestions(episodeId)
        val attemptId = originalRepository.startAttempt(episodeId)
        originalQuestions.forEach { question ->
            assertTrue(originalRepository.recordAnswer(
                attemptId, question.id, originalRepository.getCorrectChoiceId(question.id)!!
            ))
        }
        val originalScore = originalRepository.finishAttempt(attemptId)
        val originalAnswerCount = scalar(
            originalHelper.readableDatabase,
            "SELECT COUNT(*) FROM listening_answers WHERE attempt_id=$attemptId"
        )

        val legacy = originalHelper.writableDatabase
        legacy.delete("listening_episode_revisions", "episode_id=?", arrayOf(episodeId))
        legacy.execSQL("UPDATE listening_episodes SET current_revision_id=NULL WHERE id=?", arrayOf(episodeId))
        legacy.execSQL("UPDATE listening_attempts SET content_revision_id=NULL WHERE id=?", arrayOf(attemptId))
        legacy.version = 7
        originalHelper.close()

        val upgradedHelper = WordsDatabase(context)
        val upgraded = upgradedHelper.writableDatabase
        assertEquals(11, upgraded.version)
        assertTrue(upgraded.rawQuery(
            "SELECT content_revision_id FROM listening_attempts WHERE id=?",
            arrayOf(attemptId.toString())
        ).use { it.moveToFirst() && !it.isNull(0) })
        assertTrue(scalar(upgraded,
            "SELECT COUNT(*) FROM listening_revision_questions WHERE revision_id=(SELECT content_revision_id FROM listening_attempts WHERE id=$attemptId)"
        ) > 0)
        assertEquals(originalAnswerCount, scalar(
            upgraded, "SELECT COUNT(*) FROM listening_answers WHERE attempt_id=$attemptId"
        ))

        val upgradedRepository = ListeningRepository(upgradedHelper)
        assertEquals(originalQuestions.map { it.prompt }, upgradedRepository.getQuestionsForAttempt(attemptId).map { it.prompt })
        assertEquals(originalScore, upgradedRepository.finishAttempt(attemptId), 0.001f)
        upgraded.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        upgradedHelper.close()
    }

    @Test
    fun freshInstallCreatesListeningSchema() {
        context.deleteDatabase(DATABASE_NAME)
        val helper = WordsDatabase(context)
        val database = helper.writableDatabase

        assertEquals(11, database.version)
        assertTrue(tableExists(database, "listening_episodes"))
        assertTrue(tableExists(database, "listening_vocab_links"))
        assertTrue(columnExists(database, "listening_playback", "audio_revision"))
        assertTrue(tableExists(database, "listening_episode_revisions"))
        assertTrue(tableExists(database, "listening_revision_questions"))
        assertTrue(tableExists(database, "listening_content_packages"))
        assertTrue(tableExists(database, "listening_package_episodes"))
        assertTrue(columnExists(database, "listening_attempts", "content_revision_id"))
        assertTrue(columnExists(database, "listening_episode_revisions", "title"))
        assertTrue(columnExists(database, "listening_episode_revisions", "audio_url"))
        assertTrue(columnExists(database, "listening_daily_assignments", "content_revision_id"))
        assertEquals(0, scalar(database, "SELECT COUNT(*) FROM books"))

        helper.close()
    }

    private fun insertApprovedLesson(
        db: SQLiteDatabase,
        id: String,
        level: String,
        category: String,
        accent: String
    ) {
        val sourceId = "source-$id"
        val licenseId = "license-$id"
        db.insertOrThrow("listening_sources", null, android.content.ContentValues().apply {
            put("id", sourceId)
            put("title", "Original lesson source")
            put("rights_holder", "Words Project")
            put("attribution", "Original content")
            put("checked_at", 1_795_219_200_000L)
        })
        db.insertOrThrow("listening_licenses", null, android.content.ContentValues().apply {
            put("id", licenseId)
            put("license_name", "Original content license")
            put("allowed_actions", "in_app_playback,offline_download")
            put("territory", "worldwide")
        })
        db.insertOrThrow("listening_episodes", null, android.content.ContentValues().apply {
            put("id", id)
            put("title", id)
            put("description", "Validated test lesson")
            put("category", category)
            put("level", level)
            put("duration_ms", 10_000L)
            put("accent", accent)
            put("speech_rate", 1.0f)
            put("audio_url", "https://example.invalid/$id.mp3")
            put("source_id", sourceId)
            put("license_id", licenseId)
            put("status", "approved")
            put("is_demo", 0)
            put("updated_at", 1_795_219_200_000L)
        })
        val sentenceId = "$id-sentence"
        db.insertOrThrow("listening_sentences", null, android.content.ContentValues().apply {
            put("id", sentenceId)
            put("episode_id", id)
            put("ordinal", 1)
            put("start_ms", 0L)
            put("end_ms", 9_000L)
            put("english", "Small changes can matter.")
            put("chinese", "微小的改变也很重要。")
        })
        repeat(3) { index ->
            val questionId = "$id-question-$index"
            db.insertOrThrow("listening_questions", null, android.content.ContentValues().apply {
                put("id", questionId)
                put("episode_id", id)
                put("ordinal", index + 1)
                put("question_type", "main_idea")
                put("prompt", "What is the main idea?")
                put("explanation", "The lesson is about small changes.")
                put("evidence_sentence_id", sentenceId)
            })
            listOf(true, false).forEachIndexed { choiceIndex, correct ->
                db.insertOrThrow("listening_choices", null, android.content.ContentValues().apply {
                    put("question_id", questionId)
                    put("ordinal", choiceIndex + 1)
                    put("content", if (correct) "Small changes matter." else "Only large changes matter.")
                    put("is_correct", if (correct) 1 else 0)
                })
            }
        }
    }

    private fun createZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun assertInvalid(json: String) {
        try {
            ListeningContentPackageValidator.validate(json, 22)
            throw AssertionError("Invalid content package was accepted")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }

    private fun validPackageManifest(): String = """
        {
          "schema_version": 1,
          "package_id": "words-listening-core",
          "package_version": 1,
          "minimum_app_version_code": 20,
          "content_revision": "2026-11-core-01",
          "episodes": [{
            "id": "lesson-01",
            "title": "A Small Change",
            "description": "A short listening lesson.",
            "category": "daily",
            "level": "L3",
            "duration_ms": 12000,
            "accent": "American",
            "speaker": "speaker-a",
            "audio": {
              "path": "audio/lesson-01.mp3",
              "size_bytes": 1024,
              "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "mime_type": "audio/mpeg",
              "language": "en-US"
            },
            "rights": {
              "source_id": "original-source-01",
              "source_title": "Words original lesson",
              "source_url": "",
              "rights_holder": "Words Project",
              "attribution": "Original Words content",
              "checked_at": 1795219200000,
              "license_id": "words-original-license",
              "license_name": "Original content license",
              "license_url": "",
              "allowed_actions": ["in_app_playback", "offline_download"],
              "territory": "worldwide"
            },
            "sentences": [{
              "id": "sentence-01",
              "ordinal": 1,
              "start_ms": 0,
              "end_ms": 11000,
              "english": "Small changes can matter.",
              "chinese": "微小的改变也可能很重要。",
              "pronunciation_note": "Notice the stress on changes."
            }],
            "questions": [
              {
                "id": "question-01",
                "ordinal": 1,
                "type": "main_idea",
                "prompt": "What is the main idea?",
                "explanation": "The passage focuses on small changes.",
                "evidence_sentence_id": "sentence-01",
                "choices": [
                  {"content": "Small changes can matter.", "is_correct": true},
                  {"content": "Only large plans are useful.", "is_correct": false}
                ]
              },
              {
                "id": "question-02",
                "ordinal": 2,
                "type": "detail",
                "prompt": "What can small actions do?",
                "explanation": "Small actions can make a meaningful difference.",
                "evidence_sentence_id": "sentence-01",
                "choices": [
                  {"content": "Make a meaningful difference.", "is_correct": true},
                  {"content": "Prevent all future mistakes.", "is_correct": false}
                ]
              },
              {
                "id": "question-03",
                "ordinal": 3,
                "type": "inference",
                "prompt": "What is implied about progress?",
                "explanation": "Progress can build gradually through regular action.",
                "evidence_sentence_id": "sentence-01",
                "choices": [
                  {"content": "It can develop over time.", "is_correct": true},
                  {"content": "It must happen immediately.", "is_correct": false}
                ]
              }
            ]
          }]
        }
    """.trimIndent()

    private fun createVersion5Fixture() {
        val db = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null)
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL("""
            CREATE TABLE books (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '',
                version INTEGER NOT NULL DEFAULT 1, installed_at INTEGER NOT NULL,
                word_count INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE words (
                id INTEGER PRIMARY KEY AUTOINCREMENT, book_id TEXT NOT NULL,
                word TEXT NOT NULL COLLATE NOCASE, phonetic TEXT NOT NULL DEFAULT '',
                pos TEXT NOT NULL DEFAULT '', meaning TEXT NOT NULL, example TEXT NOT NULL DEFAULT '',
                example_zh TEXT NOT NULL DEFAULT '', importance INTEGER NOT NULL DEFAULT 3,
                favorite INTEGER NOT NULL DEFAULT 0, is_active INTEGER NOT NULL DEFAULT 1,
                corpus_frequency INTEGER NOT NULL DEFAULT -1, frequency_rank TEXT NOT NULL DEFAULT '',
                frequency_source TEXT NOT NULL DEFAULT '', UNIQUE(book_id, word),
                FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE reviews (
                word_id INTEGER PRIMARY KEY, mastery INTEGER NOT NULL DEFAULT 0,
                seen_count INTEGER NOT NULL DEFAULT 0, correct_count INTEGER NOT NULL DEFAULT 0,
                last_seen INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(word_id) REFERENCES words(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT, word_id INTEGER NOT NULL,
                rated_at INTEGER NOT NULL, rating INTEGER NOT NULL,
                FOREIGN KEY(word_id) REFERENCES words(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE word_detail_cache (
                word TEXT PRIMARY KEY COLLATE NOCASE, payload_json TEXT NOT NULL, updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE example_translation_cache (
                sentence TEXT PRIMARY KEY COLLATE NOCASE, translation TEXT NOT NULL, updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_words_book ON words(book_id)")
        db.execSQL("CREATE INDEX idx_history_date ON history(rated_at)")
        db.execSQL("INSERT INTO books(id,name,version,installed_at,word_count) VALUES('gaokao_core','高考核心词汇',3,1700000000000,1)")
        db.execSQL("""
            INSERT INTO words(id,book_id,word,phonetic,pos,meaning,importance,favorite,is_active)
            VALUES(101,'gaokao_core','abandon','/əˈbændən/','v.','放弃',5,1,1)
        """.trimIndent())
        db.execSQL("INSERT INTO reviews(word_id,mastery,seen_count,correct_count,last_seen) VALUES(101,4,12,9,1700000000000)")
        db.execSQL("INSERT INTO history(word_id,rated_at,rating) VALUES(101,1700000000000,4)")
        db.execSQL("INSERT INTO word_detail_cache(word,payload_json,updated_at) VALUES('abandon','{}',1700000000000)")
        db.execSQL("INSERT INTO example_translation_cache(sentence,translation,updated_at) VALUES('I abandon the plan.','我放弃这个计划。',1700000000000)")
        db.version = 5
        db.close()
    }

    private fun scalar(db: SQLiteDatabase, sql: String): Int =
        db.rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst()) { "No result for query: $sql" }
            cursor.getInt(0)
        }

    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (nameIndex >= 0 && cursor.moveToNext()) if (cursor.getString(nameIndex) == column) found = true
            found
        }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table))
            .use { it.moveToFirst() }

    companion object {
        private const val DATABASE_NAME = "words_local.db"
    }
}
