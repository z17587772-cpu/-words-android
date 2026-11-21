package com.words.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Listening data is isolated from WordsDatabase so the new module can evolve
 * without risking existing vocabulary, review and favorite records.
 */
class ListeningDatabase(context: Context) :
    SQLiteOpenHelper(context, "words_listening.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE listening_items (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                type TEXT NOT NULL,
                difficulty TEXT NOT NULL DEFAULT 'advanced',
                topic TEXT NOT NULL,
                duration_seconds INTEGER NOT NULL DEFAULT 0,
                audio_url TEXT NOT NULL,
                transcript TEXT NOT NULL DEFAULT '',
                translation TEXT NOT NULL DEFAULT '',
                source_name TEXT NOT NULL,
                source_url TEXT NOT NULL,
                license_name TEXT NOT NULL,
                license_url TEXT NOT NULL,
                content_version INTEGER NOT NULL DEFAULT 1,
                published_at INTEGER NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE listening_progress (
                item_id TEXT PRIMARY KEY,
                last_position_ms INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 0,
                attempts INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(item_id) REFERENCES listening_items(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE listening_daily_plans (
                plan_date TEXT PRIMARY KEY,
                item_ids TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_listening_type ON listening_items(type, is_active)")
        db.execSQL("CREATE INDEX idx_listening_difficulty ON listening_items(difficulty, is_active)")
        db.execSQL("CREATE INDEX idx_listening_topic ON listening_items(topic, is_active)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema migrations must be additive and preserve user progress.
    }

    fun upsertItems(items: List<ListeningItem>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            items.forEach { item ->
                require(item.id.isNotBlank() && item.title.isNotBlank())
                require(item.audioUrl.startsWith("https://"))
                require(item.sourceName.isNotBlank() && item.sourceUrl.startsWith("https://"))
                require(item.licenseName.isNotBlank() && item.licenseUrl.startsWith("https://"))
                val values = ContentValues().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("description", item.description)
                    put("type", item.type.key)
                    put("difficulty", item.difficulty.key)
                    put("topic", item.topic)
                    put("duration_seconds", item.durationSeconds.coerceAtLeast(0))
                    put("audio_url", item.audioUrl)
                    put("transcript", item.transcript)
                    put("translation", item.translation)
                    put("source_name", item.sourceName)
                    put("source_url", item.sourceUrl)
                    put("license_name", item.licenseName)
                    put("license_url", item.licenseUrl)
                    put("content_version", item.contentVersion.coerceAtLeast(1))
                    put("published_at", item.publishedAt)
                    put("is_active", if (item.isActive) 1 else 0)
                }
                db.insertWithOnConflict(
                    "listening_items", null, values, SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getActiveItems(): List<ListeningItem> = readableDatabase.rawQuery(
        """SELECT id,title,description,type,difficulty,topic,duration_seconds,audio_url,
           transcript,translation,source_name,source_url,license_name,license_url,
           content_version,published_at,is_active
           FROM listening_items WHERE is_active=1 ORDER BY published_at DESC""",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ListeningItem(
                        id = cursor.getString(0),
                        title = cursor.getString(1),
                        description = cursor.getString(2),
                        type = ListeningType.fromKey(cursor.getString(3)),
                        difficulty = ListeningDifficulty.fromKey(cursor.getString(4)),
                        topic = cursor.getString(5),
                        durationSeconds = cursor.getInt(6),
                        audioUrl = cursor.getString(7),
                        transcript = cursor.getString(8),
                        translation = cursor.getString(9),
                        sourceName = cursor.getString(10),
                        sourceUrl = cursor.getString(11),
                        licenseName = cursor.getString(12),
                        licenseUrl = cursor.getString(13),
                        contentVersion = cursor.getInt(14),
                        publishedAt = cursor.getLong(15),
                        isActive = cursor.getInt(16) == 1
                    )
                )
            }
        }
    }

    fun getProgress(itemId: String): ListeningProgress? =
        readableDatabase.rawQuery(
            """SELECT item_id,last_position_ms,completed,attempts,updated_at
               FROM listening_progress WHERE item_id=?""",
            arrayOf(itemId)
        ).use { c ->
            if (!c.moveToFirst()) null else ListeningProgress(
                itemId = c.getString(0),
                lastPositionMs = c.getLong(1),
                completed = c.getInt(2) == 1,
                attempts = c.getInt(3),
                updatedAt = c.getLong(4)
            )
        }

    fun saveProgress(progress: ListeningProgress) {
        writableDatabase.insertWithOnConflict(
            "listening_progress",
            null,
            ContentValues().apply {
                put("item_id", progress.itemId)
                put("last_position_ms", progress.lastPositionMs.coerceAtLeast(0L))
                put("completed", if (progress.completed) 1 else 0)
                put("attempts", progress.attempts.coerceAtLeast(0))
                put("updated_at", progress.updatedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getDailyPlanIds(date: String): List<String>? =
        readableDatabase.rawQuery(
            "SELECT item_ids FROM listening_daily_plans WHERE plan_date=?",
            arrayOf(date)
        ).use { c ->
            if (!c.moveToFirst()) null
            else c.getString(0).split('\n').filter { it.isNotBlank() }
        }

    fun saveDailyPlan(date: String, itemIds: List<String>) {
        writableDatabase.insertWithOnConflict(
            "listening_daily_plans",
            null,
            ContentValues().apply {
                put("plan_date", date)
                put("item_ids", itemIds.distinct().joinToString("\n"))
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getDailyPlan(date: String): DailyListeningPlan? {
        val ids = getDailyPlanIds(date) ?: return null
        val byId = getActiveItems().associateBy { it.id }
        val items = ids.mapNotNull { byId[it] }
        return if (items.isEmpty()) null else DailyListeningPlan(date, items)
    }
}
