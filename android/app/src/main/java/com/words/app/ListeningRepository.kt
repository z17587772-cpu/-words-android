package com.words.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate

class ListeningRepository(private val helper: WordsDatabase) {
    private val database: SQLiteDatabase
        get() = helper.writableDatabase

    fun ensureDemoSeeded() {
        val exists = helper.readableDatabase.rawQuery(
            "SELECT 1 FROM listening_episodes WHERE id=?",
            arrayOf(DEMO_EPISODE_ID)
        ).use { it.moveToFirst() }
        // Demo material is a preview only. Never write it into the formal
        // daily-assignment table; that table is reserved for approved courses.
        if (exists) return

        val db = database
        db.beginTransaction()
        try {
            db.insertOrThrow("listening_sources", null, ContentValues().apply {
                put("id", "words_original_demo")
                put("title", "words 原创系统语音演示材料")
                put("source_url", "")
                put("rights_holder", "words project")
                put("attribution", "原创演示文本；系统 TTS 合成语音")
                put("checked_at", System.currentTimeMillis())
            })
            db.insertOrThrow("listening_licenses", null, ContentValues().apply {
                put("id", "words_original_demo_license")
                put("license_name", "Original demonstration content")
                put("license_url", "")
                put("allowed_actions", "in_app_demo")
                put("territory", "local_demo")
                put("notes", "原创演示文本；不得将系统 TTS 语音误标为真人录音或正式课程音源。")
            })
            db.insertOrThrow("listening_episodes", null, ContentValues().apply {
                put("id", DEMO_EPISODE_ID)
                put("title", "Why Small Habits Matter")
                put("description", "通过一个学习习惯的例子，理解持续练习如何帮助我们掌握新技能。")
                put("category", "daily")
                put("level", "L2")
                put("duration_ms", 85000)
                put("accent", "System English TTS")
                put("speech_rate", 1.0)
                putNull("audio_url")
                put("source_id", "words_original_demo")
                put("license_id", "words_original_demo_license")
                put("status", "demo")
                put("is_demo", 1)
                put("updated_at", System.currentTimeMillis())
            })

            val sentenceSeeds = listOf(
                SentenceSeed("s01", 1, "Learning a new skill often seems difficult at first.", "刚开始学习一项新技能时，事情往往显得很困难。", "注意 often 的非重读音节，以及 at first 中的自然连读。"),
                SentenceSeed("s02", 2, "Many people believe success depends mainly on talent.", "许多人认为，成功主要取决于天赋。", "depends 后的 mainly 承担主要信息重音；on 在句中通常弱读。"),
                SentenceSeed("s03", 3, "But small, regular actions can make a meaningful difference.", "但小而规律的行动也能带来有意义的改变。", "small 和 regular 是信息重点；can 在自然语流中常弱读。"),
                SentenceSeed("s04", 4, "A short practice session is easier to repeat than a long session that happens only once.", "与只发生一次的长时间练习相比，短时间练习更容易重复。", "easier to 中的 to 常弱读；注意 session 的重音位置。"),
                SentenceSeed("s05", 5, "When practice becomes part of a daily routine, people spend less energy deciding when to begin.", "当练习成为日常安排的一部分时，人们就不必花那么多精力决定何时开始。", "part of a 中的 of a 会自然弱化并连读；routine 的重音在第二音节。"),
                SentenceSeed("s06", 6, "The goal is not to work perfectly, but to return consistently, notice mistakes, and make one small improvement.", "目标不是做到完美，而是持续回来练习、发现错误，并作出一点小改进。", "not to 和 but to 中的 to 都是非重读成分；注意并列动词的节奏。"),
                SentenceSeed("s07", 7, "For example, a student learning English might listen to a short passage every morning and write down one useful expression.", "例如，学习英语的学生可以每天早晨听一段短材料，并记下一条有用的表达。", "might 中的 t 可能不明显；listen to 中 to 通常弱读。"),
                SentenceSeed("s08", 8, "The next day, the student can review that expression and try to use it in a new sentence.", "第二天，学生可以复习那条表达，并尝试在新句子中使用它。", "can 和 to 都可能弱读；注意 try to 的自然衔接。"),
                SentenceSeed("s09", 9, "Over time, these small steps can build confidence and stronger listening habits.", "随着时间推移，这些小步骤能够建立信心，并形成更好的听力习惯。", "over time 常作为一个意群；stronger 和 listening 是信息重点。"),
                SentenceSeed("s10", 10, "Progress may be gradual, but consistency gives learning a clear direction.", "进步或许是渐进的，但坚持能让学习拥有清晰的方向。", "but 前后有轻微停顿；may be 中 be 通常弱读。")
            )
            sentenceSeeds.forEach { seed ->
                db.insertOrThrow("listening_sentences", null, ContentValues().apply {
                    put("id", seed.id)
                    put("episode_id", DEMO_EPISODE_ID)
                    put("ordinal", seed.ordinal)
                    put("start_ms", 0)
                    put("end_ms", 0)
                    put("english", seed.english)
                    put("chinese", seed.chinese)
                    put("pronunciation_note", seed.pronunciationNote)
                })
            }

            val questionSeeds = listOf(
                QuestionSeed("q01", 1, "main_idea", "What is the main idea of the passage?", "文章强调，持续、规律的小练习能够帮助人们逐渐掌握新技能。重点在于长期坚持，而不是一次投入大量时间。", "s03", listOf("Talent is the only key to success.", "Small regular actions support learning.", "Long study sessions are always better.", "Learning a new skill should be avoided."), 1),
                QuestionSeed("q02", 2, "detail", "Why can a short practice session be useful?", "原文指出，短时间的练习更容易重复，因此更容易成为稳定的日常安排。", "s04", listOf("It is easier to repeat.", "It removes every mistake.", "It requires no attention.", "It guarantees instant success."), 0),
                QuestionSeed("q03", 3, "detail", "What does the student do every morning in the example?", "学生每天早晨听一段短材料，并记下一条有用表达。注意题目问的是每天早晨做的事，不是第二天的复习活动。", "s07", listOf("Reads a long textbook chapter.", "Writes a complete essay.", "Listens to a short passage and notes an expression.", "Practises speaking with a teacher."), 2),
                QuestionSeed("q04", 4, "inference", "What can be inferred about building a learning habit?", "短练习更容易重复，并能逐步成为日常安排。因此，建立习惯依赖可持续的行动，而不是偶尔一次的高强度投入。", "s05", listOf("It depends entirely on natural ability.", "It is supported by actions that are easy to repeat.", "It should stop when progress is gradual.", "It can only happen in a classroom."), 1),
                QuestionSeed("q05", 5, "attitude", "What is the speaker's attitude toward gradual progress?", "说话者承认进步可能比较缓慢，但认为坚持能为学习提供方向。整体态度积极、务实。", "s10", listOf("Impatient and doubtful.", "Negative and discouraging.", "Practical and encouraging.", "Uninterested and distant."), 2)
            )
            questionSeeds.forEach { seed ->
                db.insertOrThrow("listening_questions", null, ContentValues().apply {
                    put("id", seed.id)
                    put("episode_id", DEMO_EPISODE_ID)
                    put("ordinal", seed.ordinal)
                    put("question_type", seed.type)
                    put("prompt", seed.prompt)
                    put("explanation", seed.explanation)
                    put("evidence_sentence_id", seed.evidenceSentenceId)
                })
                seed.options.forEachIndexed { index, option ->
                    db.insertOrThrow("listening_choices", null, ContentValues().apply {
                        put("question_id", seed.id)
                        put("ordinal", index)
                        put("content", option)
                        put("is_correct", if (index == seed.correctIndex) 1 else 0)
                    })
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getTodayEpisodeId(): String = helper.readableDatabase.rawQuery(
        "SELECT episode_id FROM listening_daily_assignments WHERE assignment_date=? LIMIT 1",
        arrayOf(LocalDate.now().toString())
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else DEMO_EPISODE_ID
    }

    fun getEpisode(id: String): ListeningEpisode? = helper.readableDatabase.rawQuery(
        """SELECT id,title,description,category,level,duration_ms,accent,speech_rate,audio_url,is_demo
           FROM listening_episodes WHERE id=? AND status IN ('approved','demo') LIMIT 1""",
        arrayOf(id)
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else ListeningEpisode(
            id = cursor.getString(0), title = cursor.getString(1), description = cursor.getString(2),
            category = cursor.getString(3), level = cursor.getString(4), durationMs = cursor.getLong(5),
            accent = cursor.getString(6), speechRate = cursor.getFloat(7),
            audioUrl = if (cursor.isNull(8)) null else cursor.getString(8), isDemo = cursor.getInt(9) == 1
        )
    }

    fun getEpisodes(): List<ListeningEpisode> = helper.readableDatabase.rawQuery(
        """SELECT id,title,description,category,level,duration_ms,accent,speech_rate,audio_url,is_demo
           FROM listening_episodes WHERE status IN ('approved','demo') ORDER BY is_demo ASC,title ASC""",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                ListeningEpisode(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                    cursor.getString(4), cursor.getLong(5), cursor.getString(6), cursor.getFloat(7),
                    if (cursor.isNull(8)) null else cursor.getString(8), cursor.getInt(9) == 1
                )
            )
        }
    }

    fun getSentences(episodeId: String): List<ListeningSentence> =
        helper.readableDatabase.rawQuery(
            """SELECT id,episode_id,ordinal,start_ms,end_ms,english,chinese,pronunciation_note
               FROM listening_sentences WHERE episode_id=? ORDER BY ordinal""",
            arrayOf(episodeId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    ListeningSentence(
                        cursor.getString(0), cursor.getString(1), cursor.getInt(2),
                        cursor.getLong(3), cursor.getLong(4), cursor.getString(5),
                        cursor.getString(6), cursor.getString(7)
                    )
                )
            }
        }

    fun getQuestions(episodeId: String): List<ListeningQuestion> =
        helper.readableDatabase.rawQuery(
            """SELECT id,episode_id,ordinal,question_type,prompt,explanation,evidence_sentence_id
               FROM listening_questions WHERE episode_id=? ORDER BY ordinal""",
            arrayOf(episodeId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val questionId = cursor.getString(0)
                    val evidence = if (cursor.isNull(6)) null else cursor.getString(6)
                    add(ListeningQuestion(
                        questionId, cursor.getString(1), cursor.getInt(2), cursor.getString(3),
                        cursor.getString(4), cursor.getString(5), evidence, getOptions(questionId)
                    ))
                }
            }
        }

    private fun getOptions(questionId: String): List<ListeningOption> =
        helper.readableDatabase.rawQuery(
            "SELECT id,content FROM listening_choices WHERE question_id=? ORDER BY ordinal",
            arrayOf(questionId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    ListeningOption(cursor.getLong(0), cursor.getString(1))
                )
            }
        }

    private fun isCorrectChoice(questionId: String, choiceId: Long): Boolean =
        helper.readableDatabase.rawQuery(
            "SELECT is_correct FROM listening_choices WHERE question_id=? AND id=? LIMIT 1",
            arrayOf(questionId, choiceId.toString())
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }

    fun getCorrectChoiceId(questionId: String): Long? =
        helper.readableDatabase.rawQuery(
            "SELECT id FROM listening_choices WHERE question_id=? AND is_correct=1 LIMIT 1",
            arrayOf(questionId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    fun startAttempt(episodeId: String): Long = database.insertOrThrow(
        "listening_attempts", null, ContentValues().apply {
            put("episode_id", episodeId)
            put("started_at", System.currentTimeMillis())
            put("stage", "blind")
        }
    )

    fun recordAnswer(attemptId: Long, questionId: String, choiceId: Long): Boolean {
        val correct = isCorrectChoice(questionId, choiceId)
        database.insertWithOnConflict("listening_answers", null, ContentValues().apply {
            put("attempt_id", attemptId)
            put("question_id", questionId)
            put("selected_choice_id", choiceId)
            put("is_correct", if (correct) 1 else 0)
            put("attempt_no", 1)
            put("answered_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return correct
    }

    fun finishAttempt(attemptId: Long, score: Float) {
        database.update(
            "listening_attempts",
            ContentValues().apply {
                put("completed_at", System.currentTimeMillis())
                put("stage", "completed")
                put("first_score", score)
                put("final_score", score)
            },
            "id=?",
            arrayOf(attemptId.toString())
        )
    }

    fun completedAttemptCount(): Int = helper.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM listening_attempts WHERE completed_at IS NOT NULL", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun isBookmarked(episodeId: String): Boolean = helper.readableDatabase.rawQuery(
        "SELECT 1 FROM listening_bookmarks WHERE episode_id=?",
        arrayOf(episodeId)
    ).use { it.moveToFirst() }

    fun setBookmarked(episodeId: String, bookmarked: Boolean) {
        if (bookmarked) {
            database.insertWithOnConflict(
                "listening_bookmarks", null,
                ContentValues().apply {
                    put("episode_id", episodeId)
                    put("created_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
        } else {
            database.delete("listening_bookmarks", "episode_id=?", arrayOf(episodeId))
        }
    }

    fun savePlayback(episodeId: String, positionMs: Long, speed: Float) {
        database.insertWithOnConflict(
            "listening_playback", null,
            ContentValues().apply {
                put("episode_id", episodeId)
                put("position_ms", positionMs.coerceAtLeast(0))
                put("playback_speed", speed.coerceIn(0.5f, 2.0f))
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private data class SentenceSeed(
        val id: String, val ordinal: Int, val english: String, val chinese: String, val pronunciationNote: String
    )

    private data class QuestionSeed(
        val id: String, val ordinal: Int, val type: String, val prompt: String,
        val explanation: String, val evidenceSentenceId: String, val options: List<String>, val correctIndex: Int
    )

    companion object {
        const val DEMO_EPISODE_ID = "demo_learning_habits"
    }
}
