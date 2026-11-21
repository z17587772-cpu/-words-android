package com.words.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

class WordsDatabase(context: Context) : SQLiteOpenHelper(context, "words_local.db", null, 12) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE books (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                version INTEGER NOT NULL DEFAULT 1,
                installed_at INTEGER NOT NULL,
                word_count INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE words (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id TEXT NOT NULL,
                word TEXT NOT NULL COLLATE NOCASE,
                phonetic TEXT NOT NULL DEFAULT '',
                pos TEXT NOT NULL DEFAULT '',
                meaning TEXT NOT NULL,
                example TEXT NOT NULL DEFAULT '',
                example_zh TEXT NOT NULL DEFAULT '',
                importance INTEGER NOT NULL DEFAULT 3,
                favorite INTEGER NOT NULL DEFAULT 0,
                is_active INTEGER NOT NULL DEFAULT 1,
                corpus_frequency INTEGER NOT NULL DEFAULT -1,
                frequency_rank TEXT NOT NULL DEFAULT '',
                frequency_source TEXT NOT NULL DEFAULT '',
                UNIQUE(book_id, word),
                FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE reviews (
                word_id INTEGER PRIMARY KEY,
                mastery INTEGER NOT NULL DEFAULT 0,
                seen_count INTEGER NOT NULL DEFAULT 0,
                correct_count INTEGER NOT NULL DEFAULT 0,
                last_seen INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(word_id) REFERENCES words(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word_id INTEGER NOT NULL,
                rated_at INTEGER NOT NULL,
                rating INTEGER NOT NULL,
                FOREIGN KEY(word_id) REFERENCES words(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE word_detail_cache (
                word TEXT PRIMARY KEY COLLATE NOCASE,
                payload_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE example_translation_cache (
                sentence TEXT PRIMARY KEY COLLATE NOCASE,
                translation TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_words_book ON words(book_id)")
        db.execSQL("CREATE INDEX idx_history_date ON history(rated_at)")
        createListeningTables(db)
        createListeningRevisionTables(db)
        createListeningPackageTables(db)
        createListeningDiscoveryTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE words ADD COLUMN corpus_frequency INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE words ADD COLUMN frequency_rank TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE words ADD COLUMN frequency_source TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS word_detail_cache (
                    word TEXT PRIMARY KEY COLLATE NOCASE,
                    payload_json TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())
        }
        if (oldVersion < 4) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS example_translation_cache (
                    sentence TEXT PRIMARY KEY COLLATE NOCASE,
                    translation TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE words ADD COLUMN is_active INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 6) createListeningTables(db)
        if (oldVersion < 7 && !hasColumn(db, "listening_playback", "audio_revision")) {
            db.execSQL("ALTER TABLE listening_playback ADD COLUMN audio_revision TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 8) {
            createListeningRevisionTables(db)
            migrateLegacyListeningRevisions(db)
        }
        if (oldVersion < 9) {
            if (hasTable(db, "listening_daily_assignments") && !hasColumn(db, "listening_daily_assignments", "content_revision_id")) {
                db.execSQL("ALTER TABLE listening_daily_assignments ADD COLUMN content_revision_id TEXT")
            }
            if (hasTable(db, "listening_daily_assignments")) db.execSQL("""
                UPDATE listening_daily_assignments
                SET content_revision_id=(
                    SELECT current_revision_id FROM listening_episodes
                    WHERE listening_episodes.id=listening_daily_assignments.episode_id
                )
                WHERE content_revision_id IS NULL OR content_revision_id=''
            """.trimIndent())
        }
        if (oldVersion < 10) {
            ensureRevisionMetadataColumns(db)
            backfillRevisionMetadata(db)
        }
        if (oldVersion < 11) createListeningPackageTables(db)
        if (oldVersion < 12) createListeningDiscoveryTables(db)
    }

    private fun hasTable(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table)
        ).use { it.moveToFirst() }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (nameIndex >= 0 && cursor.moveToNext()) if (cursor.getString(nameIndex) == column) found = true
            found
        }

    private fun createListeningTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_sources (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                source_url TEXT NOT NULL DEFAULT '',
                rights_holder TEXT NOT NULL DEFAULT '',
                attribution TEXT NOT NULL DEFAULT '',
                checked_at INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_licenses (
                id TEXT PRIMARY KEY,
                license_name TEXT NOT NULL,
                license_url TEXT NOT NULL DEFAULT '',
                allowed_actions TEXT NOT NULL DEFAULT '',
                territory TEXT NOT NULL DEFAULT '',
                expires_at INTEGER,
                notes TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_episodes (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL,
                level TEXT NOT NULL CHECK(level IN ('L1','L2','L3','L4')),
                duration_ms INTEGER NOT NULL DEFAULT 0,
                accent TEXT NOT NULL DEFAULT 'American',
                speech_rate REAL NOT NULL DEFAULT 1.0,
                audio_url TEXT,
                source_id TEXT,
                license_id TEXT,
                status TEXT NOT NULL DEFAULT 'approved',
                is_demo INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(source_id) REFERENCES listening_sources(id),
                FOREIGN KEY(license_id) REFERENCES listening_licenses(id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_sentences (
                id TEXT PRIMARY KEY,
                episode_id TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                start_ms INTEGER NOT NULL DEFAULT 0,
                end_ms INTEGER NOT NULL DEFAULT 0,
                english TEXT NOT NULL,
                chinese TEXT NOT NULL DEFAULT '',
                pronunciation_note TEXT NOT NULL DEFAULT '',
                UNIQUE(episode_id, ordinal),
                FOREIGN KEY(episode_id) REFERENCES listening_episodes(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_questions (
                id TEXT PRIMARY KEY,
                episode_id TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                question_type TEXT NOT NULL,
                prompt TEXT NOT NULL,
                explanation TEXT NOT NULL DEFAULT '',
                evidence_sentence_id TEXT,
                UNIQUE(episode_id, ordinal),
                FOREIGN KEY(episode_id) REFERENCES listening_episodes(id) ON DELETE CASCADE,
                FOREIGN KEY(evidence_sentence_id) REFERENCES listening_sentences(id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_choices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                question_id TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                content TEXT NOT NULL,
                is_correct INTEGER NOT NULL DEFAULT 0,
                UNIQUE(question_id, ordinal),
                FOREIGN KEY(question_id) REFERENCES listening_questions(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_daily_assignments (
                assignment_date TEXT PRIMARY KEY,
                episode_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(episode_id) REFERENCES listening_episodes(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_attempts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                episode_id TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                completed_at INTEGER,
                stage TEXT NOT NULL DEFAULT 'intro',
                first_score REAL,
                final_score REAL,
                FOREIGN KEY(episode_id) REFERENCES listening_episodes(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_answers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                attempt_id INTEGER NOT NULL,
                question_id TEXT NOT NULL,
                selected_choice_id INTEGER,
                is_correct INTEGER NOT NULL DEFAULT 0,
                attempt_no INTEGER NOT NULL DEFAULT 1,
                answered_at INTEGER NOT NULL,
                UNIQUE(attempt_id, question_id, attempt_no),
                FOREIGN KEY(attempt_id) REFERENCES listening_attempts(id) ON DELETE CASCADE,
                FOREIGN KEY(question_id) REFERENCES listening_questions(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_playback (
                episode_id TEXT PRIMARY KEY,
                position_ms INTEGER NOT NULL DEFAULT 0,
                playback_speed REAL NOT NULL DEFAULT 1.0,
                audio_revision TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(episode_id) REFERENCES listening_episodes(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_bookmarks (
                episode_id TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(episode_id) REFERENCES listening_episodes(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_vocab_links (
                episode_id TEXT NOT NULL,
                sentence_id TEXT NOT NULL,
                word_id INTEGER NOT NULL,
                PRIMARY KEY(episode_id, sentence_id, word_id),
                FOREIGN KEY(episode_id) REFERENCES listening_episodes(id) ON DELETE CASCADE,
                FOREIGN KEY(sentence_id) REFERENCES listening_sentences(id) ON DELETE CASCADE,
                FOREIGN KEY(word_id) REFERENCES words(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_episodes_level ON listening_episodes(level, category)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_sentences_episode ON listening_sentences(episode_id, ordinal)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_questions_episode ON listening_questions(episode_id, ordinal)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_attempts_episode ON listening_attempts(episode_id, started_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_answers_attempt ON listening_answers(attempt_id, question_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_daily_episode ON listening_daily_assignments(episode_id)")
    }


    private fun createListeningRevisionTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_episode_revisions (
                revision_id TEXT PRIMARY KEY,
                episode_id TEXT NOT NULL,
                revision_hash TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT '',
                level TEXT NOT NULL DEFAULT 'L1',
                duration_ms INTEGER NOT NULL DEFAULT 0,
                accent TEXT NOT NULL DEFAULT '',
                speech_rate REAL NOT NULL DEFAULT 1.0,
                audio_url TEXT,
                is_demo INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(episode_id) REFERENCES listening_episodes(id) ON DELETE CASCADE,
                UNIQUE(episode_id, revision_id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_revision_sentences (
                revision_id TEXT NOT NULL,
                sentence_id TEXT NOT NULL,
                episode_id TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                english TEXT NOT NULL,
                chinese TEXT NOT NULL,
                pronunciation_note TEXT NOT NULL,
                PRIMARY KEY(revision_id, sentence_id),
                UNIQUE(revision_id, ordinal),
                FOREIGN KEY(revision_id) REFERENCES listening_episode_revisions(revision_id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_revision_questions (
                revision_id TEXT NOT NULL,
                question_id TEXT NOT NULL,
                episode_id TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                question_type TEXT NOT NULL,
                prompt TEXT NOT NULL,
                explanation TEXT NOT NULL,
                evidence_sentence_id TEXT,
                PRIMARY KEY(revision_id, question_id),
                UNIQUE(revision_id, ordinal),
                FOREIGN KEY(revision_id) REFERENCES listening_episode_revisions(revision_id) ON DELETE CASCADE,
                FOREIGN KEY(revision_id, evidence_sentence_id)
                    REFERENCES listening_revision_sentences(revision_id, sentence_id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_revision_choices (
                revision_id TEXT NOT NULL,
                question_id TEXT NOT NULL,
                choice_id INTEGER NOT NULL,
                ordinal INTEGER NOT NULL,
                content TEXT NOT NULL,
                is_correct INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(revision_id, choice_id),
                UNIQUE(revision_id, question_id, ordinal),
                FOREIGN KEY(revision_id, question_id)
                    REFERENCES listening_revision_questions(revision_id, question_id) ON DELETE CASCADE
            )
        """.trimIndent())
        if (!hasColumn(db, "listening_episodes", "current_revision_id")) {
            db.execSQL("ALTER TABLE listening_episodes ADD COLUMN current_revision_id TEXT")
        }
        if (hasTable(db, "listening_attempts") && !hasColumn(db, "listening_attempts", "content_revision_id")) {
            db.execSQL("ALTER TABLE listening_attempts ADD COLUMN content_revision_id TEXT")
        }
        if (hasTable(db, "listening_daily_assignments") && !hasColumn(db, "listening_daily_assignments", "content_revision_id")) {
            db.execSQL("ALTER TABLE listening_daily_assignments ADD COLUMN content_revision_id TEXT")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_revision_sentences ON listening_revision_sentences(revision_id, ordinal)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_revision_questions ON listening_revision_questions(revision_id, ordinal)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_revision_choices ON listening_revision_choices(revision_id, question_id, ordinal)")
        if (hasTable(db, "listening_attempts")) db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_attempt_revision ON listening_attempts(content_revision_id)")
    }



    private fun createListeningPackageTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_content_packages (
                package_id TEXT PRIMARY KEY,
                package_version INTEGER NOT NULL CHECK(package_version > 0),
                package_digest TEXT NOT NULL,
                content_revision TEXT NOT NULL,
                imported_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_package_episodes (
                package_id TEXT NOT NULL,
                episode_id TEXT NOT NULL UNIQUE,
                revision_id TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
                PRIMARY KEY(package_id, episode_id),
                FOREIGN KEY(package_id) REFERENCES listening_content_packages(package_id) ON DELETE RESTRICT,
                FOREIGN KEY(episode_id) REFERENCES listening_episodes(id) ON DELETE RESTRICT,
                FOREIGN KEY(episode_id,revision_id) REFERENCES listening_episode_revisions(episode_id,revision_id) ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_package_active ON listening_package_episodes(package_id,is_active)")
    }

    private fun createListeningDiscoveryTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_discovered_resources (
                id TEXT PRIMARY KEY,
                provider TEXT NOT NULL,
                provider_item_id TEXT NOT NULL,
                canonical_url TEXT NOT NULL DEFAULT '',
                media_url TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                language TEXT NOT NULL DEFAULT '',
                duration_ms INTEGER NOT NULL DEFAULT 0 CHECK(duration_ms >= 0),
                creator TEXT NOT NULL DEFAULT '',
                license_name TEXT NOT NULL DEFAULT '',
                license_url TEXT NOT NULL DEFAULT '',
                rights_status TEXT NOT NULL DEFAULT 'unknown'
                    CHECK(rights_status IN ('unknown','review_required','cleared','rejected','withdrawn')),
                allowed_actions TEXT NOT NULL DEFAULT '',
                attribution TEXT NOT NULL DEFAULT '',
                territory TEXT NOT NULL DEFAULT '',
                mime_type TEXT NOT NULL DEFAULT '',
                expected_size_bytes INTEGER NOT NULL DEFAULT 0 CHECK(expected_size_bytes >= 0),
                expected_sha256 TEXT NOT NULL DEFAULT '',
                local_file_path TEXT NOT NULL DEFAULT '',
                content_sha256 TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'discovered'
                    CHECK(status IN ('discovered','eligible','queued','downloading','downloaded','rejected','expired','failed')),
                discovered_at INTEGER NOT NULL,
                downloaded_at INTEGER,
                last_checked_at INTEGER NOT NULL DEFAULT 0,
                etag TEXT NOT NULL DEFAULT '',
                pinned INTEGER NOT NULL DEFAULT 0 CHECK(pinned IN (0,1)),
                last_played_at INTEGER,
                UNIQUE(provider, provider_item_id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listening_provider_state (
                provider TEXT PRIMARY KEY,
                cursor_json TEXT NOT NULL DEFAULT '{}',
                last_success_at INTEGER NOT NULL DEFAULT 0,
                last_attempt_at INTEGER NOT NULL DEFAULT 0,
                backoff_until INTEGER NOT NULL DEFAULT 0,
                etag TEXT NOT NULL DEFAULT '',
                last_error TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_discovered_status ON listening_discovered_resources(status, discovered_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_discovered_rights ON listening_discovered_resources(rights_status, status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_discovered_last_played ON listening_discovered_resources(last_played_at)")
    }

    private fun ensureRevisionMetadataColumns(db: SQLiteDatabase) {
        val columns = listOf(
            "title TEXT NOT NULL DEFAULT ''",
            "description TEXT NOT NULL DEFAULT ''",
            "category TEXT NOT NULL DEFAULT ''",
            "level TEXT NOT NULL DEFAULT 'L1'",
            "duration_ms INTEGER NOT NULL DEFAULT 0",
            "accent TEXT NOT NULL DEFAULT ''",
            "speech_rate REAL NOT NULL DEFAULT 1.0",
            "audio_url TEXT",
            "is_demo INTEGER NOT NULL DEFAULT 0",
            "updated_at INTEGER NOT NULL DEFAULT 0"
        )
        columns.forEach { definition ->
            val name = definition.substringBefore(' ')
            if (!hasColumn(db, "listening_episode_revisions", name)) {
                db.execSQL("ALTER TABLE listening_episode_revisions ADD COLUMN $definition")
            }
        }
    }

    private fun backfillRevisionMetadata(db: SQLiteDatabase) {
        if (!hasTable(db, "listening_episodes") || !hasTable(db, "listening_episode_revisions") ||
            !hasColumn(db, "listening_episodes", "updated_at")) return
        db.execSQL("""
            UPDATE listening_episode_revisions
            SET title=COALESCE((SELECT title FROM listening_episodes e WHERE e.id=episode_id),''),
                description=COALESCE((SELECT description FROM listening_episodes e WHERE e.id=episode_id),''),
                category=COALESCE((SELECT category FROM listening_episodes e WHERE e.id=episode_id),''),
                level=COALESCE((SELECT level FROM listening_episodes e WHERE e.id=episode_id),'L1'),
                duration_ms=COALESCE((SELECT duration_ms FROM listening_episodes e WHERE e.id=episode_id),0),
                accent=COALESCE((SELECT accent FROM listening_episodes e WHERE e.id=episode_id),''),
                speech_rate=COALESCE((SELECT speech_rate FROM listening_episodes e WHERE e.id=episode_id),1.0),
                audio_url=(SELECT audio_url FROM listening_episodes e WHERE e.id=episode_id),
                is_demo=COALESCE((SELECT is_demo FROM listening_episodes e WHERE e.id=episode_id),0),
                updated_at=COALESCE((SELECT updated_at FROM listening_episodes e WHERE e.id=episode_id),0)
            WHERE title='' OR updated_at=0
        """.trimIndent())
    }

    private fun migrateLegacyListeningRevisions(db: SQLiteDatabase) {
        // Some historical test/partial databases contain only a playback stub.
        // Real v6/v7 installs have the full listening schema; never make an
        // otherwise valid upgrade fail because an optional legacy table is absent.
        if (!hasColumn(db, "listening_episodes", "updated_at") ||
            !hasTable(db, "listening_sentences") ||
            !hasTable(db, "listening_questions") ||
            !hasTable(db, "listening_choices") ||
            !hasTable(db, "listening_attempts")) return

        db.execSQL("""
            INSERT OR IGNORE INTO listening_episode_revisions(
                revision_id,episode_id,revision_hash,title,description,category,level,duration_ms,
                accent,speech_rate,audio_url,is_demo,updated_at,created_at
            )
            SELECT 'legacy:' || id || ':' || updated_at,id,'legacy-v7',title,description,category,level,
                   duration_ms,accent,speech_rate,audio_url,is_demo,updated_at,updated_at
            FROM listening_episodes
        """.trimIndent())
        db.execSQL("""
            UPDATE listening_episodes
            SET current_revision_id='legacy:' || id || ':' || updated_at
            WHERE current_revision_id IS NULL OR current_revision_id=''
        """.trimIndent())
        db.execSQL("""
            INSERT OR IGNORE INTO listening_revision_sentences(
                revision_id,sentence_id,episode_id,ordinal,start_ms,end_ms,english,chinese,pronunciation_note
            )
            SELECT e.current_revision_id,s.id,s.episode_id,s.ordinal,s.start_ms,s.end_ms,
                   s.english,s.chinese,s.pronunciation_note
            FROM listening_sentences s
            JOIN listening_episodes e ON e.id=s.episode_id
            WHERE e.current_revision_id IS NOT NULL
        """.trimIndent())
        db.execSQL("""
            INSERT OR IGNORE INTO listening_revision_questions(
                revision_id,question_id,episode_id,ordinal,question_type,prompt,explanation,evidence_sentence_id
            )
            SELECT e.current_revision_id,q.id,q.episode_id,q.ordinal,q.question_type,q.prompt,
                   q.explanation,q.evidence_sentence_id
            FROM listening_questions q
            JOIN listening_episodes e ON e.id=q.episode_id
            WHERE e.current_revision_id IS NOT NULL
        """.trimIndent())
        db.execSQL("""
            INSERT OR IGNORE INTO listening_revision_choices(
                revision_id,question_id,choice_id,ordinal,content,is_correct
            )
            SELECT e.current_revision_id,c.question_id,c.id,c.ordinal,c.content,c.is_correct
            FROM listening_choices c
            JOIN listening_questions q ON q.id=c.question_id
            JOIN listening_episodes e ON e.id=q.episode_id
            WHERE e.current_revision_id IS NOT NULL
        """.trimIndent())
        db.execSQL("""
            UPDATE listening_attempts
            SET content_revision_id=(
                SELECT current_revision_id FROM listening_episodes
                WHERE listening_episodes.id=listening_attempts.episode_id
            )
            WHERE content_revision_id IS NULL OR content_revision_id=''
        """.trimIndent())
    }

    fun seedBuiltInPack(context: Context) {
        // Every JSON file in assets/books is an independent offline wordbook.
        // Newer pack versions are installed on app upgrade while existing review
        // rows for matching words remain intact.
        val bookFiles = context.assets.list("books").orEmpty()
            .filter { it.endsWith(".json", ignoreCase = true) }
            .sorted()
        for (fileName in bookFiles) {
            val json = context.assets.open("books/$fileName")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val pack = parsePack(json)
            val installedVersion = readableDatabase.rawQuery(
                "SELECT version FROM books WHERE id=?", arrayOf(pack.id)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }
            if (installedVersion == null || installedVersion < pack.version) {
                installPack(pack)
            }
        }
    }

    fun installPack(pack: VocabularyPack) {
        require(pack.id.isNotBlank() && pack.name.isNotBlank()) { "词书缺少名称或 ID" }
        require(pack.words.isNotEmpty()) { "词书中没有单词" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val bookValues = ContentValues().apply {
                put("id", pack.id)
                put("name", pack.name)
                put("description", pack.description)
                put("version", pack.version)
                put("installed_at", System.currentTimeMillis())
                put("word_count", pack.words.size)
            }
            val bookExists = db.rawQuery("SELECT 1 FROM books WHERE id=?", arrayOf(pack.id)).use { it.moveToFirst() }
            if (bookExists) db.update("books", bookValues, "id=?", arrayOf(pack.id))
            else db.insert("books", null, bookValues)
            // Deactivate removed headwords without deleting their review/history rows.
            db.execSQL("UPDATE words SET is_active=0 WHERE book_id=?", arrayOf(pack.id))
            val existingIds = HashMap<String, Long>()
            db.rawQuery("SELECT id,word FROM words WHERE book_id=?", arrayOf(pack.id)).use { cursor ->
                while (cursor.moveToNext()) existingIds[cursor.getString(1).trim().lowercase()] = cursor.getLong(0)
            }
            pack.words.forEach { item ->
                val existingId = existingIds[item.word.trim().lowercase()]
                val values = ContentValues().apply {
                    put("book_id", pack.id)
                    put("word", item.word.trim())
                    put("phonetic", item.phonetic)
                    put("pos", item.partOfSpeech)
                    put("meaning", item.meaning)
                    put("example", item.example)
                    put("example_zh", item.exampleZh)
                    put("importance", item.importance.coerceIn(1, 5))
                    put("is_active", 1)
                put("corpus_frequency", item.corpusFrequency)
                put("frequency_rank", item.frequencyRank)
                put("frequency_source", item.frequencySource)
                }
                if (existingId == null) db.insert("words", null, values)
                else db.update("words", values, "id=?", arrayOf(existingId.toString()))
            }
            db.execSQL("UPDATE books SET word_count=(SELECT COUNT(*) FROM words WHERE words.book_id=books.id AND words.is_active=1) WHERE id=?", arrayOf(pack.id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun installedBooks(): List<VocabularyBook> = readableDatabase.rawQuery(
        "SELECT id,name,description,version,word_count FROM books ORDER BY installed_at DESC", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(VocabularyBook(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4)))
        }
    }

    fun getWords(bookId: String): List<VocabularyWord> = readableDatabase.rawQuery(
        """SELECT w.id,w.book_id,w.word,w.phonetic,w.pos,w.meaning,w.example,w.example_zh,w.importance,
           COALESCE(r.mastery,0),COALESCE(r.seen_count,0),COALESCE(r.correct_count,0),COALESCE(r.last_seen,0),w.favorite,w.corpus_frequency,w.frequency_rank,w.frequency_source
           FROM words w LEFT JOIN reviews r ON r.word_id=w.id WHERE w.book_id=? AND w.is_active=1 ORDER BY w.word COLLATE NOCASE""",
        arrayOf(bookId)
    ).use(::readWords)

    fun getWord(id: Long): VocabularyWord? = readableDatabase.rawQuery(
        """SELECT w.id,w.book_id,w.word,w.phonetic,w.pos,w.meaning,w.example,w.example_zh,w.importance,
           COALESCE(r.mastery,0),COALESCE(r.seen_count,0),COALESCE(r.correct_count,0),COALESCE(r.last_seen,0),w.favorite,w.corpus_frequency,w.frequency_rank,w.frequency_source
           FROM words w LEFT JOIN reviews r ON r.word_id=w.id WHERE w.id=?""",
        arrayOf(id.toString())
    ).use { readWords(it).firstOrNull() }

    /**
     * Returns saved words from every installed book, ordered by book and headword.
     * Favorite state is deliberately independent of study/review history.
     */
    fun getFavoriteWords(): List<VocabularyWord> = readableDatabase.rawQuery(
        """SELECT w.id,w.book_id,w.word,w.phonetic,w.pos,w.meaning,w.example,w.example_zh,w.importance,
           COALESCE(r.mastery,0),COALESCE(r.seen_count,0),COALESCE(r.correct_count,0),COALESCE(r.last_seen,0),w.favorite,w.corpus_frequency,w.frequency_rank,w.frequency_source
           FROM words w LEFT JOIN reviews r ON r.word_id=w.id
           WHERE w.favorite=1 AND w.is_active=1
           ORDER BY w.book_id COLLATE NOCASE, w.word COLLATE NOCASE""",
        null
    ).use(::readWords)

    fun favoriteCountAll(): Int = scalar("SELECT COUNT(*) FROM words WHERE favorite=1 AND is_active=1")

    /** Cached online dictionary responses are stored locally for later offline reading. */
    fun getDictionaryCache(word: String): String? = readableDatabase.rawQuery(
        "SELECT payload_json FROM word_detail_cache WHERE word=?",
        arrayOf(word.trim().lowercase())
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun putDictionaryCache(word: String, payloadJson: String) {
        writableDatabase.insertWithOnConflict(
            "word_detail_cache",
            null,
            ContentValues().apply {
                put("word", word.trim().lowercase())
                put("payload_json", payloadJson)
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getExampleTranslation(sentence: String): String? = readableDatabase.rawQuery(
        "SELECT translation FROM example_translation_cache WHERE sentence=?",
        arrayOf(sentence.trim())
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun putExampleTranslation(sentence: String, translation: String) {
        writableDatabase.insertWithOnConflict(
            "example_translation_cache",
            null,
            ContentValues().apply {
                put("sentence", sentence.trim())
                put("translation", translation.trim())
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun recentWordIds(bookId: String, limit: Int = 60): List<Long> = readableDatabase.rawQuery(
        "SELECT h.word_id FROM history h JOIN words w ON w.id=h.word_id WHERE w.book_id=? AND w.is_active=1 GROUP BY h.word_id ORDER BY MAX(h.rated_at) DESC LIMIT ?",
        arrayOf(bookId, limit.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    fun saveReview(wordId: Long, rating: Int) {
        val previous = readableDatabase.rawQuery("SELECT mastery,seen_count,correct_count FROM reviews WHERE word_id=?", arrayOf(wordId.toString())).use {
            if (it.moveToFirst()) Triple(it.getInt(0), it.getInt(1), it.getInt(2)) else Triple(0, 0, 0)
        }
        val nextMastery = when (rating) {
            2 -> (previous.first + 1).coerceAtMost(3)
            1 -> 2
            else -> 1
        }
        val now = System.currentTimeMillis()
        val reviewValues = ContentValues().apply {
            put("word_id", wordId)
            put("mastery", nextMastery)
            put("seen_count", previous.second + 1)
            put("correct_count", previous.third + if (rating == 2) 1 else 0)
            put("last_seen", now)
        }
        writableDatabase.insertWithOnConflict("reviews", null, reviewValues, SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.insert("history", null, ContentValues().apply {
            put("word_id", wordId)
            put("rated_at", now)
            put("rating", rating)
        })
    }

    fun setFavorite(wordId: Long, favorite: Boolean) {
        writableDatabase.update("words", ContentValues().apply { put("favorite", if (favorite) 1 else 0) }, "id=?", arrayOf(wordId.toString()))
    }

    fun dailyReviewedCount(): Int {
        val now = java.time.LocalDate.now()
        val start = now.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM history WHERE rated_at>=?", arrayOf(start.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun totalReviewedCount(): Int = scalar("SELECT COUNT(*) FROM history")
    fun knownCount(bookId: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM reviews r JOIN words w ON w.id=r.word_id WHERE w.book_id=? AND w.is_active=1 AND r.mastery=3", arrayOf(bookId)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun weakCount(bookId: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM reviews r JOIN words w ON w.id=r.word_id WHERE w.book_id=? AND w.is_active=1 AND r.mastery IN (1,2)", arrayOf(bookId)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun favoriteCount(bookId: String): Int = scalar("SELECT COUNT(*) FROM words WHERE book_id=? AND favorite=1 AND is_active=1", bookId)
    fun totalWordCount(bookId: String): Int = scalar("SELECT COUNT(*) FROM words WHERE book_id=? AND is_active=1", bookId)

    fun searchWords(bookId: String, query: String): List<VocabularyWord> = readableDatabase.rawQuery(
        """SELECT w.id,w.book_id,w.word,w.phonetic,w.pos,w.meaning,w.example,w.example_zh,w.importance,
           COALESCE(r.mastery,0),COALESCE(r.seen_count,0),COALESCE(r.correct_count,0),COALESCE(r.last_seen,0),w.favorite,w.corpus_frequency,w.frequency_rank,w.frequency_source
           FROM words w LEFT JOIN reviews r ON r.word_id=w.id
           WHERE w.book_id=? AND w.is_active=1 AND (w.word LIKE ? OR w.meaning LIKE ?) ORDER BY w.word LIMIT 100""",
        arrayOf(bookId, "%$query%", "%$query%")
    ).use(::readWords)

    private fun scalar(sql: String, arg: String? = null): Int = readableDatabase.rawQuery(sql, if (arg == null) null else arrayOf(arg)).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    private fun readWords(c: android.database.Cursor): List<VocabularyWord> = buildList {
        while (c.moveToNext()) add(
            VocabularyWord(
                id = c.getLong(0), bookId = c.getString(1), word = c.getString(2), phonetic = c.getString(3),
                partOfSpeech = c.getString(4), meaning = c.getString(5), example = c.getString(6), exampleZh = c.getString(7),
                importance = c.getInt(8), mastery = c.getInt(9), seenCount = c.getInt(10), correctCount = c.getInt(11),
                lastSeenAt = c.getLong(12), favorite = c.getInt(13) == 1,
                corpusFrequency = c.getInt(14), frequencyRank = c.getString(15), frequencySource = c.getString(16)
            )
        )
    }

    companion object {
        const val BUILTIN_BOOK_ID = "gaokao_standard_2025"

        fun parsePack(json: String): VocabularyPack {
            val root = JSONObject(json)
            val wordsJson = root.optJSONArray("words") ?: throw IllegalArgumentException("词书格式错误：缺少 words 数组")
            require(wordsJson.length() in 1..50_000) { "词书单词数量必须在 1 到 50000 之间" }
            val words = ArrayList<PackWord>(wordsJson.length())
            for (i in 0 until wordsJson.length()) {
                val item = wordsJson.getJSONObject(i)
                val english = item.optString("word").trim()
                val meaning = item.optString("meaning").trim()
                require(english.isNotBlank() && meaning.isNotBlank()) { "第 ${i + 1} 个单词缺少英文或释义" }
                words += PackWord(
                    word = english,
                    phonetic = item.optString("phonetic"),
                    partOfSpeech = item.optString("pos"),
                    meaning = meaning,
                    example = item.optString("example"),
                    exampleZh = item.optString("example_zh"),
                    importance = item.optInt("importance", 3).coerceIn(1, 5),
                    corpusFrequency = item.optInt("corpus_frequency", -1),
                    frequencyRank = item.optString("frequency_rank"),
                    frequencySource = item.optString("frequency_source")
                )
            }
            val packId = root.optString("id").ifBlank { "imported_${System.currentTimeMillis()}" }
            val name = root.optString("name").ifBlank { "导入词书" }
            return VocabularyPack(packId, name, root.optString("description"), root.optInt("version", 1), words)
        }
    }
}
