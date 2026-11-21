package com.words.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun upgradingV5ToV6PreservesVocabularyAndLearningHistory() {
        context.deleteDatabase(DATABASE_NAME)
        createVersion5Fixture()

        val helper = WordsDatabase(context)
        val upgraded = helper.writableDatabase

        assertEquals(6, upgraded.version)
        assertEquals(1, scalar(upgraded, "SELECT COUNT(*) FROM books WHERE id='gaokao_core'"))
        assertEquals(1, scalar(upgraded, "SELECT COUNT(*) FROM words WHERE id=101 AND favorite=1 AND word='abandon'"))
        assertEquals(4, scalar(upgraded, "SELECT mastery FROM reviews WHERE word_id=101"))
        assertEquals(1, scalar(upgraded, "SELECT COUNT(*) FROM history WHERE word_id=101 AND rated_at=1700000000000"))
        assertEquals(1, scalar(upgraded, "SELECT COUNT(*) FROM word_detail_cache WHERE word='abandon'"))
        assertEquals(1, scalar(upgraded, "SELECT COUNT(*) FROM example_translation_cache WHERE sentence='I abandon the plan.'"))

        assertTrue(tableExists(upgraded, "listening_episodes"))
        assertTrue(tableExists(upgraded, "listening_attempts"))
        assertTrue(tableExists(upgraded, "listening_playback"))
        upgraded.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            assertEquals("No foreign-key violations after migration", false, cursor.moveToFirst())
        }

        helper.close()
    }

    @Test
    fun playbackStateRoundTripsAndDemoNeverBecomesDailyAssignment() {
        context.deleteDatabase(DATABASE_NAME)
        val helper = WordsDatabase(context)
        val repository = ListeningRepository(helper)
        repository.ensureDemoSeeded()

        val episodeId = ListeningRepository.DEMO_EPISODE_ID
        assertEquals(null, repository.getPlayback(episodeId))
        repository.savePlayback(episodeId, -500L, Float.NaN)
        val first = repository.getPlayback(episodeId)
        assertEquals(0L, first?.positionMs)
        assertEquals(1.0f, first?.speed ?: 0f, 0.001f)

        repository.savePlayback(episodeId, 42_500L, 1.5f)
        val restored = repository.getPlayback(episodeId)
        assertEquals(episodeId, restored?.episodeId)
        assertEquals(42_500L, restored?.positionMs)
        assertEquals(1.5f, restored?.speed ?: 0f, 0.001f)

        assertEquals(
            0,
            scalar(helper.readableDatabase, "SELECT COUNT(*) FROM listening_daily_assignments WHERE episode_id='$episodeId'")
        )
        helper.close()
    }

    @Test
    fun contentPackageValidatorAcceptsCompleteManifestAndRejectsUnsafeReferences() {
        val valid = ListeningContentPackageValidator.validate(validPackageManifest(), 22)
        assertEquals("words-listening-core", valid.packageId)
        assertEquals(1, valid.episodes.size)
        assertEquals(1, valid.episodes.single().questions.single().choices.count { it.isCorrect })

        assertInvalid(validPackageManifest().replace("audio/lesson-01.mp3", "../outside.mp3"))
        assertInvalid(validPackageManifest().replace("a".repeat(64), "not-a-checksum"))
        assertInvalid(validPackageManifest().replace("\"evidence_sentence_id\": \"sentence-01\"", "\"evidence_sentence_id\": \"missing-sentence\""))
        assertInvalid(validPackageManifest().replace("\"in_app_playback\"", "\"streaming_only\""))
        assertInvalid(validPackageManifest().replace("\"schema_version\": 1", "\"schema_version\": 99"))
    }

    @Test
    fun freshInstallCreatesListeningSchema() {
        context.deleteDatabase(DATABASE_NAME)
        val helper = WordsDatabase(context)
        val database = helper.writableDatabase

        assertEquals(6, database.version)
        assertTrue(tableExists(database, "listening_episodes"))
        assertTrue(tableExists(database, "listening_vocab_links"))
        assertEquals(0, scalar(database, "SELECT COUNT(*) FROM books"))

        helper.close()
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
              "rights_holder": "Words Project",
              "license_name": "Original content license",
              "license_url": "",
              "allowed_actions": ["in_app_playback"],
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
            "questions": [{
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
            }]
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

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table))
            .use { it.moveToFirst() }

    companion object {
        private const val DATABASE_NAME = "words_local.db"
    }
}
