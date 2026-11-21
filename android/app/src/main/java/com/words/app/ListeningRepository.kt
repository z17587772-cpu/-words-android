package com.words.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate

data class ListeningAttemptProgress(
    val id: Long,
    val episodeId: String,
    val stage: String,
    val isCompleted: Boolean
)

data class ListeningAttemptAnswer(
    val selectedChoiceId: Long?,
    val isCorrect: Boolean
)

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

    fun getTodayEpisodeId(): String =
        assignDailyEpisode(LocalDate.now().toString()) ?: DEMO_EPISODE_ID

    fun assignDailyEpisode(assignmentDate: String, nowMs: Long = System.currentTimeMillis()): String? {
        val date = try {
            LocalDate.parse(assignmentDate)
        } catch (_: Exception) {
            throw IllegalArgumentException("assignmentDate must use ISO-8601 yyyy-MM-dd")
        }
        val db = database
        db.beginTransaction()
        try {
            // Remove legacy preview assignments written by pre-fix builds.
            db.delete(
                "listening_daily_assignments",
                "episode_id IN (SELECT id FROM listening_episodes WHERE is_demo=1)",
                null
            )
            val existing = getAssignedEpisodeId(assignmentDate)
            if (existing != null) {
                db.setTransactionSuccessful()
                return existing
            }

            val candidates = getEligibleEpisodes(nowMs)
            val history = getRecentAssignmentHistory(assignmentDate)
            val selected = ListeningDailyPlanner.choose(candidates, history, assignmentDate)
            if (selected != null) {
                val assignedRevision = ensureCurrentRevision(db, selected.id)
                db.insertWithOnConflict(
                    "listening_daily_assignments", null,
                    ContentValues().apply {
                        put("assignment_date", assignmentDate)
                        put("episode_id", selected.id)
                        put("content_revision_id", assignedRevision)
                        put("created_at", nowMs)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return getAssignedEpisodeId(assignmentDate)
    }


    private fun ensureCurrentRevision(db: SQLiteDatabase, episodeId: String): String {
        val current = db.rawQuery(
            "SELECT current_revision_id FROM listening_episodes WHERE id=?",
            arrayOf(episodeId)
        ).use { cursor ->
            require(cursor.moveToFirst()) { "Listening episode does not exist: $episodeId" }
            if (cursor.isNull(0)) null else cursor.getString(0).takeIf { it.isNotBlank() }
        }
        if (current != null) return current
        val updatedAt = db.rawQuery(
            "SELECT updated_at FROM listening_episodes WHERE id=?",
            arrayOf(episodeId)
        ).use { cursor -> require(cursor.moveToFirst()); cursor.getLong(0) }
        val revisionId = "legacy:$episodeId:$updatedAt"
        db.execSQL("""
            INSERT OR IGNORE INTO listening_episode_revisions(
                revision_id,episode_id,revision_hash,title,description,category,level,duration_ms,
                accent,speech_rate,audio_url,is_demo,updated_at,created_at
            )
            SELECT ?,id,'legacy-runtime',title,description,category,level,duration_ms,
                   accent,speech_rate,audio_url,is_demo,updated_at,updated_at
            FROM listening_episodes WHERE id=?
        """.trimIndent(), arrayOf(revisionId, episodeId))
        db.execSQL("""
            INSERT OR IGNORE INTO listening_revision_sentences(
                revision_id,sentence_id,episode_id,ordinal,start_ms,end_ms,english,chinese,pronunciation_note
            )
            SELECT ?,id,episode_id,ordinal,start_ms,end_ms,english,chinese,pronunciation_note
            FROM listening_sentences WHERE episode_id=?
        """.trimIndent(), arrayOf(revisionId, episodeId))
        db.execSQL("""
            INSERT OR IGNORE INTO listening_revision_questions(
                revision_id,question_id,episode_id,ordinal,question_type,prompt,explanation,evidence_sentence_id
            )
            SELECT ?,id,episode_id,ordinal,question_type,prompt,explanation,evidence_sentence_id
            FROM listening_questions WHERE episode_id=?
        """.trimIndent(), arrayOf(revisionId, episodeId))
        db.execSQL("""
            INSERT OR IGNORE INTO listening_revision_choices(
                revision_id,question_id,choice_id,ordinal,content,is_correct
            )
            SELECT ?,c.question_id,c.id,c.ordinal,c.content,c.is_correct
            FROM listening_choices c
            JOIN listening_questions q ON q.id=c.question_id
            WHERE q.episode_id=?
        """.trimIndent(), arrayOf(revisionId, episodeId))
        db.execSQL(
            "UPDATE listening_episodes SET current_revision_id=? WHERE id=? AND (current_revision_id IS NULL OR current_revision_id='')",
            arrayOf(revisionId, episodeId)
        )
        return db.rawQuery(
            "SELECT current_revision_id FROM listening_episodes WHERE id=?",
            arrayOf(episodeId)
        ).use { cursor -> require(cursor.moveToFirst()); cursor.getString(0) }
    }

    private fun getAssignedEpisodeId(assignmentDate: String): String? =
        helper.readableDatabase.rawQuery(
            "SELECT episode_id FROM listening_daily_assignments WHERE assignment_date=? LIMIT 1",
            arrayOf(assignmentDate)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun getEligibleEpisodes(nowMs: Long): List<ListeningEpisode> {
        val db = helper.writableDatabase
        // Legacy approved lessons are snapshotted before planning. New package
        // imports already publish their immutable revision in the same DB txn.
        val approvedEpisodeIds = db.rawQuery(
            "SELECT id FROM listening_episodes WHERE status='approved' AND is_demo=0",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        approvedEpisodeIds.forEach { ensureCurrentRevision(db, it) }

        return db.rawQuery(
            """SELECT e.id,r.title,r.description,r.category,r.level,r.duration_ms,
                      r.accent,r.speech_rate,r.audio_url,r.is_demo,r.updated_at
               FROM listening_episodes e
               JOIN listening_episode_revisions r
                 ON r.revision_id=e.current_revision_id AND r.episode_id=e.id
               JOIN listening_sources s ON s.id=e.source_id
               JOIN listening_licenses l ON l.id=e.license_id
               LEFT JOIN listening_package_episodes pe ON pe.episode_id=e.id
               WHERE e.status='approved' AND r.is_demo=0
                 AND (pe.episode_id IS NULL OR pe.is_active=1)
                 AND r.audio_url IS NOT NULL AND TRIM(r.audio_url)<>''
                 AND r.duration_ms>0 AND TRIM(r.category)<>'' AND TRIM(r.accent)<>''
                 AND TRIM(s.title)<>'' AND TRIM(s.rights_holder)<>'' AND TRIM(s.attribution)<>''
                 AND s.checked_at>0
                 AND TRIM(l.license_name)<>'' AND TRIM(l.territory)<>''
                 AND (',' || l.allowed_actions || ',') LIKE '%,in_app_playback,%'
                 AND (',' || l.allowed_actions || ',') LIKE '%,offline_download,%'
                 AND (l.expires_at IS NULL OR l.expires_at>?)
                 AND EXISTS (
                     SELECT 1 FROM listening_revision_sentences ss
                     WHERE ss.revision_id=r.revision_id
                 )
                 AND NOT EXISTS (
                     SELECT 1 FROM listening_revision_sentences ss
                     WHERE ss.revision_id=r.revision_id
                       AND (ss.start_ms<0 OR ss.end_ms<=ss.start_ms OR ss.end_ms>r.duration_ms
                            OR TRIM(ss.english)='' OR TRIM(ss.chinese)='')
                 )
                 AND (SELECT COUNT(*) FROM listening_revision_questions q
                      WHERE q.revision_id=r.revision_id) BETWEEN 3 AND 8
                 AND NOT EXISTS (
                     SELECT 1 FROM listening_revision_questions q
                     WHERE q.revision_id=r.revision_id
                       AND (TRIM(q.prompt)='' OR TRIM(q.explanation)=''
                            OR q.evidence_sentence_id IS NULL
                            OR NOT EXISTS (
                                SELECT 1 FROM listening_revision_sentences ev
                                WHERE ev.revision_id=q.revision_id
                                  AND ev.sentence_id=q.evidence_sentence_id
                            )
                            OR (SELECT COUNT(*) FROM listening_revision_choices ch
                                WHERE ch.revision_id=q.revision_id
                                  AND ch.question_id=q.question_id) NOT BETWEEN 2 AND 6
                            OR (SELECT SUM(ch.is_correct) FROM listening_revision_choices ch
                                WHERE ch.revision_id=q.revision_id
                                  AND ch.question_id=q.question_id)<>1)
                 )
               ORDER BY e.id""",
            arrayOf(nowMs.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    ListeningEpisode(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4), cursor.getLong(5),
                        cursor.getString(6), cursor.getFloat(7),
                        if (cursor.isNull(8)) null else cursor.getString(8),
                        cursor.getInt(9) == 1, cursor.getLong(10)
                    )
                )
            }
        }
    }

    private fun getRecentAssignmentHistory(
        beforeDate: String
    ): List<ListeningAssignmentHistory> =
        helper.readableDatabase.rawQuery(
            """SELECT e.id,
                      COALESCE((
                          SELECT revision.category
                          FROM listening_daily_assignments latest
                          JOIN listening_episode_revisions revision
                            ON revision.revision_id=latest.content_revision_id
                          WHERE latest.episode_id=e.id
                          ORDER BY latest.assignment_date DESC LIMIT 1
                      ),e.category),
                      COALESCE((
                          SELECT revision.level
                          FROM listening_daily_assignments latest
                          JOIN listening_episode_revisions revision
                            ON revision.revision_id=latest.content_revision_id
                          WHERE latest.episode_id=e.id
                          ORDER BY latest.assignment_date DESC LIMIT 1
                      ),e.level),
                      COALESCE((
                          SELECT revision.accent
                          FROM listening_daily_assignments latest
                          JOIN listening_episode_revisions revision
                            ON revision.revision_id=latest.content_revision_id
                          WHERE latest.episode_id=e.id
                          ORDER BY latest.assignment_date DESC LIMIT 1
                      ),e.accent),
                      COUNT(*),MAX(d.assignment_date)
               FROM listening_daily_assignments d
               JOIN listening_episodes e ON e.id=d.episode_id
               WHERE d.assignment_date<?
               GROUP BY e.id
               ORDER BY MAX(d.assignment_date) DESC""",
            arrayOf(beforeDate)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    ListeningAssignmentHistory(
                        episodeId = cursor.getString(0),
                        category = cursor.getString(1),
                        level = cursor.getString(2),
                        accent = cursor.getString(3),
                        assignmentCount = cursor.getInt(4),
                        lastAssignedDate = cursor.getString(5)
                    )
                )
            }
        }

    fun getEpisode(id: String): ListeningEpisode? = helper.readableDatabase.rawQuery(
        """SELECT id,title,description,category,level,duration_ms,accent,speech_rate,audio_url,is_demo,updated_at
           FROM listening_episodes WHERE id=? AND status IN ('approved','demo') LIMIT 1""",
        arrayOf(id)
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else ListeningEpisode(
            id = cursor.getString(0), title = cursor.getString(1), description = cursor.getString(2),
            category = cursor.getString(3), level = cursor.getString(4), durationMs = cursor.getLong(5),
            accent = cursor.getString(6), speechRate = cursor.getFloat(7),
            audioUrl = if (cursor.isNull(8)) null else cursor.getString(8), isDemo = cursor.getInt(9) == 1,
            updatedAt = cursor.getLong(10)
        )
    }

    fun getEpisodeForDailyAssignment(assignmentDate: String, episodeId: String): ListeningEpisode? =
        helper.readableDatabase.rawQuery(
            """SELECT revision.episode_id,revision.title,revision.description,revision.category,
                      revision.level,revision.duration_ms,revision.accent,revision.speech_rate,
                      revision.audio_url,revision.is_demo,revision.updated_at
               FROM listening_daily_assignments assignment
               JOIN listening_episode_revisions revision
                 ON revision.revision_id=assignment.content_revision_id
                AND revision.episode_id=assignment.episode_id
               WHERE assignment.assignment_date=? AND assignment.episode_id=? LIMIT 1""",
            arrayOf(assignmentDate, episodeId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ListeningEpisode(
                id = cursor.getString(0), title = cursor.getString(1), description = cursor.getString(2),
                category = cursor.getString(3), level = cursor.getString(4), durationMs = cursor.getLong(5),
                accent = cursor.getString(6), speechRate = cursor.getFloat(7),
                audioUrl = if (cursor.isNull(8)) null else cursor.getString(8),
                isDemo = cursor.getInt(9) == 1, updatedAt = cursor.getLong(10)
            )
        }

    fun getEpisodeForAttempt(attemptId: Long): ListeningEpisode? =
        helper.readableDatabase.rawQuery(
            """SELECT revision.episode_id,revision.title,revision.description,revision.category,
                      revision.level,revision.duration_ms,revision.accent,revision.speech_rate,
                      revision.audio_url,revision.is_demo,revision.updated_at
               FROM listening_attempts attempt
               JOIN listening_episode_revisions revision
                 ON revision.revision_id=attempt.content_revision_id
               WHERE attempt.id=? LIMIT 1""",
            arrayOf(attemptId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ListeningEpisode(
                id = cursor.getString(0), title = cursor.getString(1), description = cursor.getString(2),
                category = cursor.getString(3), level = cursor.getString(4), durationMs = cursor.getLong(5),
                accent = cursor.getString(6), speechRate = cursor.getFloat(7),
                audioUrl = if (cursor.isNull(8)) null else cursor.getString(8),
                isDemo = cursor.getInt(9) == 1, updatedAt = cursor.getLong(10)
            )
        }

    fun getEpisodes(): List<ListeningEpisode> = helper.readableDatabase.rawQuery(
        """SELECT id,title,description,category,level,duration_ms,accent,speech_rate,audio_url,is_demo,updated_at
           FROM listening_episodes e WHERE status IN ('approved','demo') AND (NOT EXISTS (SELECT 1 FROM listening_package_episodes pe WHERE pe.episode_id=e.id) OR EXISTS (SELECT 1 FROM listening_package_episodes pe WHERE pe.episode_id=e.id AND pe.is_active=1)) ORDER BY is_demo ASC,title ASC""",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                ListeningEpisode(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                    cursor.getString(4), cursor.getLong(5), cursor.getString(6), cursor.getFloat(7),
                    if (cursor.isNull(8)) null else cursor.getString(8), cursor.getInt(9) == 1, cursor.getLong(10)
                )
            )
        }
    }

    fun getSentences(episodeId: String): List<ListeningSentence> {
        val db = helper.writableDatabase
        val revisionId = ensureCurrentRevision(db, episodeId)
        return db.rawQuery(
            """SELECT sentence_id,episode_id,ordinal,start_ms,end_ms,english,chinese,pronunciation_note
               FROM listening_revision_sentences WHERE revision_id=? ORDER BY ordinal""",
            arrayOf(revisionId)
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
    }

    fun getSentencesForAttempt(attemptId: Long): List<ListeningSentence> {
        val revisionId = helper.readableDatabase.rawQuery(
            "SELECT content_revision_id FROM listening_attempts WHERE id=?",
            arrayOf(attemptId.toString())
        ).use { cursor ->
            require(cursor.moveToFirst()) { "Attempt does not exist: $attemptId" }
            require(!cursor.isNull(0)) { "Attempt has no pinned content revision" }
            cursor.getString(0)
        }
        return helper.readableDatabase.rawQuery(
            """SELECT sentence_id,episode_id,ordinal,start_ms,end_ms,english,chinese,pronunciation_note
               FROM listening_revision_sentences WHERE revision_id=? ORDER BY ordinal""",
            arrayOf(revisionId)
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
    }

    fun getQuestions(episodeId: String): List<ListeningQuestion> {
        val db = helper.writableDatabase
        val revisionId = ensureCurrentRevision(db, episodeId)
        return db.rawQuery(
            """SELECT question_id,episode_id,ordinal,question_type,prompt,explanation,evidence_sentence_id
               FROM listening_revision_questions WHERE revision_id=? ORDER BY ordinal""",
            arrayOf(revisionId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val questionId = cursor.getString(0)
                    val evidence = if (cursor.isNull(6)) null else cursor.getString(6)
                    add(ListeningQuestion(
                        questionId, cursor.getString(1), cursor.getInt(2), cursor.getString(3),
                        cursor.getString(4), cursor.getString(5), evidence,
                        getOptions(questionId, revisionId)
                    ))
                }
            }
        }
    }

    fun getOptions(questionId: String): List<ListeningOption> {
        val db = helper.writableDatabase
        val episodeId = db.rawQuery(
            "SELECT episode_id FROM listening_questions WHERE id=?", arrayOf(questionId)
        ).use { cursor ->
            require(cursor.moveToFirst()) { "Listening question does not exist: $questionId" }
            cursor.getString(0)
        }
        return getOptions(questionId, ensureCurrentRevision(db, episodeId))
    }

    private fun getOptions(questionId: String, revisionId: String): List<ListeningOption> =
        helper.readableDatabase.rawQuery(
            "SELECT choice_id,content FROM listening_revision_choices WHERE revision_id=? AND question_id=? ORDER BY ordinal",
            arrayOf(revisionId, questionId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(ListeningOption(cursor.getLong(0), cursor.getString(1)))
            }
        }

    fun getQuestionsForAttempt(attemptId: Long): List<ListeningQuestion> {
        val attempt = helper.readableDatabase.rawQuery(
            "SELECT episode_id,content_revision_id FROM listening_attempts WHERE id=?",
            arrayOf(attemptId.toString())
        ).use { cursor ->
            require(cursor.moveToFirst()) { "Attempt does not exist: $attemptId" }
            require(!cursor.isNull(1)) { "Attempt has no pinned content revision" }
            cursor.getString(0) to cursor.getString(1)
        }
        val (episodeId, revisionId) = attempt
        return helper.readableDatabase.rawQuery(
            """SELECT question_id,episode_id,ordinal,question_type,prompt,explanation,evidence_sentence_id
               FROM listening_revision_questions WHERE revision_id=? ORDER BY ordinal""",
            arrayOf(revisionId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val questionId = cursor.getString(0)
                    val evidence = if (cursor.isNull(6)) null else cursor.getString(6)
                    add(ListeningQuestion(
                        questionId, episodeId, cursor.getInt(2), cursor.getString(3),
                        cursor.getString(4), cursor.getString(5), evidence,
                        getOptions(questionId, revisionId)
                    ))
                }
            }
        }
    }

    fun getCorrectChoiceId(attemptId: Long, questionId: String): Long? {
        val revisionId = helper.readableDatabase.rawQuery(
            "SELECT content_revision_id FROM listening_attempts WHERE id=?",
            arrayOf(attemptId.toString())
        ).use { cursor ->
            require(cursor.moveToFirst()) { "Attempt does not exist: $attemptId" }
            require(!cursor.isNull(0)) { "Attempt has no pinned content revision" }
            cursor.getString(0)
        }
        return helper.readableDatabase.rawQuery(
            "SELECT choice_id FROM listening_revision_choices WHERE revision_id=? AND question_id=? AND is_correct=1 LIMIT 1",
            arrayOf(revisionId, questionId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    fun getCorrectChoiceId(questionId: String): Long? {
        val db = helper.writableDatabase
        val episodeId = db.rawQuery(
            "SELECT episode_id FROM listening_questions WHERE id=?", arrayOf(questionId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else return null }
        val revisionId = ensureCurrentRevision(db, episodeId)
        return db.rawQuery(
            "SELECT choice_id FROM listening_revision_choices WHERE revision_id=? AND question_id=? AND is_correct=1 LIMIT 1",
            arrayOf(revisionId, questionId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    fun getAttemptAnswerReviews(attemptId: Long): List<ListeningAnswerReview> =
        helper.readableDatabase.rawQuery(
            """SELECT q.question_id,a.selected_choice_id,selected.content,correct.choice_id,correct.content,
                      COALESCE(a.is_correct,0)
               FROM listening_attempts attempt
               JOIN listening_revision_questions q ON q.revision_id=attempt.content_revision_id
               LEFT JOIN listening_answers a
                 ON a.attempt_id=attempt.id AND a.question_id=q.question_id AND a.attempt_no=1
               LEFT JOIN listening_revision_choices selected
                 ON selected.revision_id=attempt.content_revision_id
                AND selected.question_id=q.question_id AND selected.choice_id=a.selected_choice_id
               JOIN listening_revision_choices correct
                 ON correct.revision_id=attempt.content_revision_id
                AND correct.question_id=q.question_id AND correct.is_correct=1
               WHERE attempt.id=? AND attempt.completed_at IS NOT NULL
               ORDER BY q.ordinal""",
            arrayOf(attemptId.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    ListeningAnswerReview(
                        questionId = cursor.getString(0),
                        selectedChoiceId = if (cursor.isNull(1)) null else cursor.getLong(1),
                        selectedContent = if (cursor.isNull(2)) null else cursor.getString(2),
                        correctChoiceId = cursor.getLong(3),
                        correctContent = cursor.getString(4),
                        isCorrect = cursor.getInt(5) == 1
                    )
                )
            }
        }

    fun startAttempt(episodeId: String): Long {
        val db = database
        db.beginTransaction()
        try {
            val assignedRevision = db.rawQuery(
                """SELECT content_revision_id FROM listening_daily_assignments
                   WHERE assignment_date=? AND episode_id=? LIMIT 1""",
                arrayOf(LocalDate.now().toString(), episodeId)
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
            val revisionId = assignedRevision ?: ensureCurrentRevision(db, episodeId)
            val attemptId = db.insertOrThrow(
                "listening_attempts", null, ContentValues().apply {
                    put("episode_id", episodeId)
                    put("content_revision_id", revisionId)
                    put("started_at", System.currentTimeMillis())
                    put("stage", "blind")
                }
            )
            db.setTransactionSuccessful()
            return attemptId
        } finally {
            db.endTransaction()
        }
    }

    fun getLatestIncompleteAttempt(): ListeningAttemptProgress? =
        helper.readableDatabase.rawQuery(
            """SELECT id,episode_id,stage,completed_at FROM listening_attempts
               WHERE completed_at IS NULL AND stage IN ('blind','quiz')
               ORDER BY started_at DESC,id DESC LIMIT 1""",
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ListeningAttemptProgress(
                id = cursor.getLong(0),
                episodeId = cursor.getString(1),
                stage = cursor.getString(2),
                isCompleted = !cursor.isNull(3)
            )
        }

    fun abandonAttempt(attemptId: Long) {
        database.update(
            "listening_attempts",
            ContentValues().apply { put("stage", "abandoned") },
            "id=? AND completed_at IS NULL",
            arrayOf(attemptId.toString())
        )
    }

    fun getAttemptProgress(attemptId: Long): ListeningAttemptProgress? =
        helper.readableDatabase.rawQuery(
            "SELECT id,episode_id,stage,completed_at FROM listening_attempts WHERE id=? LIMIT 1",
            arrayOf(attemptId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ListeningAttemptProgress(
                id = cursor.getLong(0),
                episodeId = cursor.getString(1),
                stage = cursor.getString(2),
                isCompleted = !cursor.isNull(3)
            )
        }

    fun getAttemptAnswer(attemptId: Long, questionId: String): ListeningAttemptAnswer? =
        helper.readableDatabase.rawQuery(
            """SELECT selected_choice_id,is_correct FROM listening_answers
               WHERE attempt_id=? AND question_id=? AND attempt_no=1 LIMIT 1""",
            arrayOf(attemptId.toString(), questionId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ListeningAttemptAnswer(
                selectedChoiceId = if (cursor.isNull(0)) null else cursor.getLong(0),
                isCorrect = cursor.getInt(1) == 1
            )
        }

    fun getAttemptCorrectCount(attemptId: Long): Int =
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM listening_answers WHERE attempt_id=? AND attempt_no=1 AND is_correct=1",
            arrayOf(attemptId.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun updateAttemptStage(attemptId: Long, stage: String) {
        require(stage == "blind" || stage == "quiz") { "Unsupported in-progress listening stage" }
        val updated = database.update(
            "listening_attempts",
            ContentValues().apply { put("stage", stage) },
            "id=? AND completed_at IS NULL",
            arrayOf(attemptId.toString())
        )
        require(updated == 1) { "Attempt is missing or already completed" }
    }

    fun recordAnswer(attemptId: Long, questionId: String, choiceId: Long): Boolean {
        val existing = helper.readableDatabase.rawQuery(
            "SELECT is_correct FROM listening_answers WHERE attempt_id=? AND question_id=? AND attempt_no=1 LIMIT 1",
            arrayOf(attemptId.toString(), questionId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) == 1 else null }
        if (existing != null) return existing

        val correct = helper.readableDatabase.rawQuery(
            """SELECT choice.is_correct
               FROM listening_attempts attempt
               JOIN listening_revision_questions question
                 ON question.revision_id=attempt.content_revision_id AND question.question_id=?
               JOIN listening_revision_choices choice
                 ON choice.revision_id=attempt.content_revision_id
                AND choice.question_id=question.question_id AND choice.choice_id=?
               WHERE attempt.id=? AND attempt.completed_at IS NULL
               LIMIT 1""",
            arrayOf(questionId, choiceId.toString(), attemptId.toString())
        ).use { cursor ->
            require(cursor.moveToFirst()) {
                "The selected choice does not belong to this question and attempt"
            }
            cursor.getInt(0) == 1
        }

        val inserted = database.insertWithOnConflict("listening_answers", null, ContentValues().apply {
            put("attempt_id", attemptId)
            put("question_id", questionId)
            put("selected_choice_id", choiceId)
            put("is_correct", if (correct) 1 else 0)
            put("attempt_no", 1)
            put("answered_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (inserted != -1L) return correct

        return helper.readableDatabase.rawQuery(
            "SELECT is_correct FROM listening_answers WHERE attempt_id=? AND question_id=? AND attempt_no=1 LIMIT 1",
            arrayOf(attemptId.toString(), questionId)
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }
    }

    fun finishAttempt(attemptId: Long): Float {
        val db = database
        db.beginTransaction()
        try {
            val state = db.rawQuery(
                "SELECT completed_at,final_score FROM listening_attempts WHERE id=? LIMIT 1",
                arrayOf(attemptId.toString())
            ).use { cursor ->
                require(cursor.moveToFirst()) { "Attempt does not exist" }
                val completed = !cursor.isNull(0)
                val savedScore = if (cursor.isNull(1)) null else cursor.getFloat(1)
                completed to savedScore
            }
            if (state.first) {
                return requireNotNull(state.second) { "Completed attempt has no persisted score" }
            }

            val result = db.rawQuery(
                """SELECT COUNT(question.question_id),
                          SUM(CASE WHEN answer.id IS NOT NULL THEN 1 ELSE 0 END),
                          SUM(CASE WHEN answer.is_correct=1 THEN 1 ELSE 0 END)
                   FROM listening_attempts attempt
                   JOIN listening_revision_questions question ON question.revision_id=attempt.content_revision_id
                   LEFT JOIN listening_answers answer
                     ON answer.attempt_id=attempt.id AND answer.question_id=question.question_id AND answer.attempt_no=1
                   WHERE attempt.id=?""",
                arrayOf(attemptId.toString())
            ).use { cursor ->
                require(cursor.moveToFirst() && cursor.getInt(0) > 0) { "Attempt has no questions" }
                val total = cursor.getInt(0)
                val answered = cursor.getInt(1)
                val correct = cursor.getInt(2)
                require(answered == total) { "Every question must be answered before completing an attempt" }
                correct.toFloat() / total.toFloat()
            }

            val updated = db.update(
                "listening_attempts",
                ContentValues().apply {
                    put("completed_at", System.currentTimeMillis())
                    put("stage", "completed")
                    put("first_score", result)
                    put("final_score", result)
                },
                "id=? AND completed_at IS NULL",
                arrayOf(attemptId.toString())
            )
            check(updated == 1) { "Attempt completion state changed unexpectedly" }
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
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

    fun getPlayback(episodeId: String, audioRevision: String? = null): ListeningPlaybackState? =
        helper.readableDatabase.rawQuery(
            "SELECT position_ms,playback_speed,audio_revision FROM listening_playback WHERE episode_id=? LIMIT 1",
            arrayOf(episodeId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ListeningPlaybackState(
                episodeId = episodeId,
                positionMs = if (audioRevision == null || cursor.getString(2) == audioRevision) cursor.getLong(0).coerceAtLeast(0L) else 0L,
                speed = cursor.getFloat(1).takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                audioRevision = cursor.getString(2)
            )
        }

    fun isKnownPlaybackRevision(
        episodeId: String,
        audioUri: String,
        audioRevision: String,
        positionMs: Long
    ): Boolean = helper.readableDatabase.rawQuery(
        """SELECT duration_ms FROM listening_episode_revisions
           WHERE episode_id=? AND audio_url=? AND is_demo=0
             AND (CAST(updated_at AS TEXT) || ':' || audio_url)=?
           LIMIT 1""",
        arrayOf(episodeId, audioUri, audioRevision)
    ).use { cursor ->
        if (!cursor.moveToFirst()) false
        else cursor.getLong(0) <= 0L || positionMs < cursor.getLong(0)
    }

    fun savePlayback(episodeId: String, positionMs: Long, speed: Float, audioRevision: String = "") {
        val safeSpeed = speed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f
        database.insertWithOnConflict(
            "listening_playback", null,
            ContentValues().apply {
                put("episode_id", episodeId)
                put("position_ms", positionMs.coerceAtLeast(0L))
                put("playback_speed", safeSpeed)
                put("audio_revision", audioRevision)
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
