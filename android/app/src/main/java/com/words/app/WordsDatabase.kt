package com.words.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

class WordsDatabase(context: Context) : SQLiteOpenHelper(context, "words_local.db", null, 3) {
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
        db.execSQL("CREATE INDEX idx_words_book ON words(book_id)")
        db.execSQL("CREATE INDEX idx_history_date ON history(rated_at)")
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
            pack.words.forEach { item ->
                val existingId = db.rawQuery(
                    "SELECT id FROM words WHERE book_id=? AND word=?",
                    arrayOf(pack.id, item.word.trim())
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                val values = ContentValues().apply {
                    put("book_id", pack.id)
                    put("word", item.word.trim())
                    put("phonetic", item.phonetic)
                    put("pos", item.partOfSpeech)
                    put("meaning", item.meaning)
                    put("example", item.example)
                    put("example_zh", item.exampleZh)
                    put("importance", item.importance.coerceIn(1, 5))
                put("corpus_frequency", item.corpusFrequency)
                put("frequency_rank", item.frequencyRank)
                put("frequency_source", item.frequencySource)
                }
                if (existingId == null) db.insert("words", null, values)
                else db.update("words", values, "id=?", arrayOf(existingId.toString()))
            }
            db.execSQL("UPDATE books SET word_count=(SELECT COUNT(*) FROM words WHERE words.book_id=books.id) WHERE id=?", arrayOf(pack.id))
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
           FROM words w LEFT JOIN reviews r ON r.word_id=w.id WHERE w.book_id=? ORDER BY w.word COLLATE NOCASE""",
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
           WHERE w.favorite=1
           ORDER BY w.book_id COLLATE NOCASE, w.word COLLATE NOCASE""",
        null
    ).use(::readWords)

    fun favoriteCountAll(): Int = scalar("SELECT COUNT(*) FROM words WHERE favorite=1")

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

    fun recentWordIds(bookId: String, limit: Int = 60): List<Long> = readableDatabase.rawQuery(
        "SELECT h.word_id FROM history h JOIN words w ON w.id=h.word_id WHERE w.book_id=? GROUP BY h.word_id ORDER BY MAX(h.rated_at) DESC LIMIT ?",
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
        "SELECT COUNT(*) FROM reviews r JOIN words w ON w.id=r.word_id WHERE w.book_id=? AND r.mastery=3", arrayOf(bookId)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun weakCount(bookId: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM reviews r JOIN words w ON w.id=r.word_id WHERE w.book_id=? AND r.mastery IN (1,2)", arrayOf(bookId)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun favoriteCount(bookId: String): Int = scalar("SELECT COUNT(*) FROM words WHERE book_id=? AND favorite=1", bookId)
    fun totalWordCount(bookId: String): Int = scalar("SELECT COUNT(*) FROM words WHERE book_id=?", bookId)

    fun searchWords(bookId: String, query: String): List<VocabularyWord> = readableDatabase.rawQuery(
        """SELECT w.id,w.book_id,w.word,w.phonetic,w.pos,w.meaning,w.example,w.example_zh,w.importance,
           COALESCE(r.mastery,0),COALESCE(r.seen_count,0),COALESCE(r.correct_count,0),COALESCE(r.last_seen,0),w.favorite,w.corpus_frequency,w.frequency_rank,w.frequency_source
           FROM words w LEFT JOIN reviews r ON r.word_id=w.id
           WHERE w.book_id=? AND (w.word LIKE ? OR w.meaning LIKE ?) ORDER BY w.word LIMIT 100""",
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
