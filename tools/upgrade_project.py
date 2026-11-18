from pathlib import Path
import re
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
MAIN = ROOT / 'app/src/main/java/com/words/app/MainActivity.kt'
MODELS = ROOT / 'app/src/main/java/com/words/app/Models.kt'
DB = ROOT / 'app/src/main/java/com/words/app/WordsDatabase.kt'
SCHED = ROOT / 'app/src/main/java/com/words/app/WeightedScheduler.kt'

# --- Models ---
s = MODELS.read_text(encoding='utf-8')
s = s.replace('    val favorite: Boolean = false\n)', '    val favorite: Boolean = false,\n    val corpusFrequency: Int = -1,\n    val frequencyRank: String = "",\n    val frequencySource: String = ""\n)', 1)
s = s.replace('    val importance: Int\n)', '    val importance: Int,\n    val corpusFrequency: Int = -1,\n    val frequencyRank: String = "",\n    val frequencySource: String = ""\n)', 1)
MODELS.write_text(s, encoding='utf-8')

# --- SQLite database migration and wordbook import ---
s = DB.read_text(encoding='utf-8')
s = s.replace('SQLiteOpenHelper(context, "words_local.db", null, 1)', 'SQLiteOpenHelper(context, "words_local.db", null, 2)')
s = s.replace("                favorite INTEGER NOT NULL DEFAULT 0,\n                UNIQUE(book_id, word),", "                favorite INTEGER NOT NULL DEFAULT 0,\n                corpus_frequency INTEGER NOT NULL DEFAULT -1,\n                frequency_rank TEXT NOT NULL DEFAULT '',\n                frequency_source TEXT NOT NULL DEFAULT '',\n                UNIQUE(book_id, word),")
s = s.replace('    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit', '''    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE words ADD COLUMN corpus_frequency INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE words ADD COLUMN frequency_rank TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE words ADD COLUMN frequency_source TEXT NOT NULL DEFAULT ''")
        }
    }''')
s = s.replace('                put("importance", item.importance.coerceIn(1, 5))\n', '                put("importance", item.importance.coerceIn(1, 5))\n                put("corpus_frequency", item.corpusFrequency)\n                put("frequency_rank", item.frequencyRank)\n                put("frequency_source", item.frequencySource)\n')
s = s.replace('COALESCE(r.last_seen,0),w.favorite\n', 'COALESCE(r.last_seen,0),w.favorite,w.corpus_frequency,w.frequency_rank,w.frequency_source\n')
s = s.replace('                lastSeenAt = c.getLong(12), favorite = c.getInt(13) == 1\n', '                lastSeenAt = c.getLong(12), favorite = c.getInt(13) == 1,\n                corpusFrequency = c.getInt(14), frequencyRank = c.getString(15), frequencySource = c.getString(16)\n')
s = s.replace('                    importance = item.optInt("importance", 3).coerceIn(1, 5)\n', '                    importance = item.optInt("importance", 3).coerceIn(1, 5),\n                    corpusFrequency = item.optInt("corpus_frequency", -1),\n                    frequencyRank = item.optString("frequency_rank"),\n                    frequencySource = item.optString("frequency_source")\n')
DB.write_text(s, encoding='utf-8')

# --- Weighted scheduler: O(n log n), separated browse/review weights ---
SCHED.write_text('''package com.words.app

import kotlin.math.ln
import kotlin.random.Random

/**
 * Weighted sampling without replacement.
 * Study ranking combines curriculum importance, third-party frequency references,
 * mastery, and time since review. Quick Browse deliberately ignores mastery/review
 * state and only uses vocabulary importance plus the optional frequency reference.
 */
object WeightedScheduler {
    fun createQueue(
        words: List<VocabularyWord>,
        recentlySeenIds: List<Long>,
        requestedCount: Int,
        now: Long = System.currentTimeMillis(),
        random: Random = Random.Default
    ): List<Long> {
        if (words.isEmpty() || requestedCount <= 0) return emptyList()
        val count = minOf(requestedCount, words.size)
        val coolingIds = cooldownIds(words, recentlySeenIds)
        val fresh = words.filter { it.id !in coolingIds }
        val cooled = words.filter { it.id in coolingIds }
            .sortedWith(compareBy<VocabularyWord> { it.lastSeenAt }.thenBy { it.word })
        val selected = weightedOrder(fresh, now, random, ::studyWeight).take(count).toMutableList()
        if (selected.size < count) {
            selected += weightedOrder(cooled, now, random, ::studyWeight).take(count - selected.size)
        }
        return selected
    }

    /**
     * Builds a complete browse cycle. Every word appears at most once in the cycle.
     * Words in the recent-view cooldown are placed at the tail, so a user can keep
     * browsing indefinitely without immediate repetition. No review state is read.
     */
    fun createBrowseQueue(
        words: List<VocabularyWord>,
        recentlyBrowsedIds: List<Long>,
        random: Random = Random.Default
    ): List<Long> {
        if (words.isEmpty()) return emptyList()
        val coolingIds = cooldownIds(words, recentlyBrowsedIds)
        val fresh = words.filter { it.id !in coolingIds }
        val recentOrder = recentlyBrowsedIds.withIndex().associate { it.value to it.index }
        val cooled = words.filter { it.id in coolingIds }
            .sortedByDescending { recentOrder[it.id] ?: Int.MIN_VALUE }
        return weightedOrder(fresh, 0L, random, ::browseWeight) + cooled.map { it.id }
    }

    fun weight(word: VocabularyWord, now: Long = System.currentTimeMillis()): Double =
        studyWeight(word, now)

    private fun cooldownIds(words: List<VocabularyWord>, recentIds: List<Long>): Set<Long> {
        val cooldownSize = minOf(30, maxOf(8, words.size / 4))
        return recentIds.take(cooldownSize).toSet()
    }

    /** Exponential-race weighted permutation: O(n log n), without replacement. */
    private fun weightedOrder(
        candidates: List<VocabularyWord>,
        now: Long,
        random: Random,
        weightOf: (VocabularyWord, Long) -> Double
    ): List<Long> = candidates.map { word ->
        val u = random.nextDouble().coerceIn(1e-12, 1.0)
        val key = -ln(u) / weightOf(word, now).coerceAtLeast(0.000001)
        key to word.id
    }.sortedBy { it.first }.map { it.second }

    private fun frequencyFactor(rank: String): Double = when (rank.lowercase()) {
        "high" -> 1.65
        "medium" -> 1.25
        "low" -> 0.85
        else -> 1.0
    }

    private fun studyWeight(word: VocabularyWord, now: Long): Double {
        val importanceFactor = word.importance.coerceIn(1, 5).toDouble().let { it * it }
        val masteryFactor = when (word.mastery.coerceIn(0, 3)) {
            0 -> 1.55 // new
            1 -> 2.35 // not remembered
            2 -> 1.45 // uncertain
            else -> 0.62 // familiar, but never permanently removed
        }
        val daysSinceSeen = if (word.lastSeenAt <= 0L) 30.0
        else ((now - word.lastSeenAt).coerceAtLeast(0L) / 86_400_000.0)
        val coverageFactor = 1.0 + minOf(2.5, ln(1.0 + daysSinceSeen) * 0.55)
        val lowExposureBoost = if (word.seenCount == 0) 1.25 else 1.0
        return (importanceFactor * frequencyFactor(word.frequencyRank) * masteryFactor * coverageFactor * lowExposureBoost)
            .coerceAtLeast(0.01)
    }

    private fun browseWeight(word: VocabularyWord, @Suppress("UNUSED_PARAMETER") now: Long): Double {
        val importance = word.importance.coerceIn(1, 5).toDouble()
        val favoriteBoost = if (word.favorite) 1.08 else 1.0
        return (importance * importance * frequencyFactor(word.frequencyRank) * favoriteBoost).coerceAtLeast(0.01)
    }
}
''', encoding='utf-8')

# --- MainActivity ---
s = MAIN.read_text(encoding='utf-8')
s = s.replace('import android.view.WindowManager\n', 'import android.view.WindowManager\nimport android.view.animation.DecelerateInterpolator\n')
s = s.replace('    private var queueIndex = 0\n', '''    private var queueIndex = 0
    private var browseQueue: MutableList<Long> = mutableListOf()
    private var browseIndex = 0
    private var browseRecentIds: MutableList<Long> = mutableListOf()
    private var browseCount = 0
''')
s = s.replace('        loadQueueFromPreferences()\n', '        loadQueueFromPreferences()\n        loadBrowseState()\n', 1)
s = s.replace('''    private fun renderPage() {
        pageHost.removeAllViews()''', '''    private fun renderPage() {
        pageHost.animate().cancel()
        pageHost.alpha = 0.92f
        pageHost.removeAllViews()''')
s = s.replace('''            TAB_HOME -> renderHome(content)''', '''            TAB_BROWSE -> renderBrowse(content)''')
s = s.replace('''        renderBottomBar()
    }

    private fun renderBottomBar()''', '''        renderBottomBar()
        pageHost.animate().alpha(1f).setDuration(190).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun renderBottomBar()''', 1)
s = s.replace('''        val tabs = listOf(
            TAB_HOME to "学习",
            TAB_STUDY to "背词",''', '''        val tabs = listOf(
            TAB_BROWSE to "随刷",
            TAB_STUDY to "背词",''')
s = s.replace('''                setPadding(dp(4), dp(4), dp(4), dp(2))
                isClickable = true''', '''                setPadding(dp(4), dp(5), dp(4), dp(4))
                if (active) background = rounded(softAccent, 0, 13)
                isClickable = true''', 1)
s = s.replace('''                when (tab) { TAB_HOME -> "⌂"; TAB_STUDY -> "▱"; TAB_BOOKS -> "▤"; TAB_STATS -> "▥"; else -> "○" },
                19f, if (active) accent else secondary, true, Gravity.CENTER''', '''                when (tab) { TAB_BROWSE -> "∞"; TAB_STUDY -> "▱"; TAB_BOOKS -> "▤"; TAB_STATS -> "▥"; else -> "○" },
                20f, if (active) accent else secondary, true, Gravity.CENTER''')

# Replace legacy learning home with independent, continuous Quick Browse page.
start = s.index('    private fun renderHome(parent: LinearLayout) {')
end = s.index('    private fun metricCard(value: String, caption: String): LinearLayout', start)
new_browse = '''    private fun renderBrowse(parent: LinearLayout) {
        val book = activeBook()
        val word = browseCurrentWord()

        val top = row()
        top.addView(text("WORDS  /  QUICK BROWSE", 10f, secondary, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(quietButton("切换词书", {
            selectedTab = TAB_BOOKS
            renderPage()
        }))
        parent.addView(top)
        parent.addView(vSpace(14))
        parent.addView(text("随刷", 29f, ink, true))
        parent.addView(vSpace(6))
        parent.addView(text("碎片时间，打开就刷。没有任务限制，也不打断背词进度。", 12f, secondary))
        parent.addView(vSpace(19))

        if (word == null) {
            val empty = card(22)
            empty.addView(text("这本词书暂时没有单词", 18f, ink, true))
            empty.addView(vSpace(8))
            empty.addView(text("请在词库中切换或导入一份离线词书。", 12f, secondary))
            parent.addView(empty)
            return
        }

        val flashCard = card(24).apply {
            minimumHeight = dp(500)
            background = rounded(white, line, 26)
            elevation = dp(1).toFloat()
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val head = row()
        head.addView(text("VOCABULARY  ·  INFINITE", 10f, secondary, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val priorityLabel = when (word.frequencyRank.lowercase()) {
            "high" -> "高频参考"
            "medium" -> "中频参考"
            "low" -> "低频参考"
            else -> "课标权重 ${word.importance}/5"
        }
        head.addView(text(priorityLabel, 10f, accent, true, Gravity.END))
        flashCard.addView(head)
        flashCard.addView(vSpace(43))
        flashCard.addView(text(word.word, 38f, ink, true, Gravity.CENTER))
        if (word.phonetic.isNotBlank()) {
            flashCard.addView(vSpace(11))
            flashCard.addView(text(word.phonetic, 14f, secondary, false, Gravity.CENTER))
        }
        if (word.partOfSpeech.isNotBlank()) {
            flashCard.addView(vSpace(7))
            flashCard.addView(text(word.partOfSpeech, 12f, secondary, false, Gravity.CENTER))
        }
        if (word.corpusFrequency >= 0) {
            flashCard.addView(vSpace(8))
            flashCard.addView(text("第三方综合语料参考：${word.corpusFrequency} 次", 10f, secondary, false, Gravity.CENTER))
        }
        flashCard.addView(vSpace(29))
        flashCard.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        flashCard.addView(vSpace(25))
        flashCard.addView(text(word.meaning, 21f, ink, true, Gravity.CENTER))
        if (word.example.isNotBlank()) {
            flashCard.addView(vSpace(24))
            flashCard.addView(text("例句", 10f, secondary, true))
            flashCard.addView(vSpace(7))
            flashCard.addView(text(word.example, 14f, ink))
            if (word.exampleZh.isNotBlank()) {
                flashCard.addView(vSpace(5))
                flashCard.addView(text(word.exampleZh, 12f, secondary))
            }
        }
        parent.addView(flashCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(vSpace(13))

        val actionRow = row()
        actionRow.addView(pillButton("播放发音", false) { speak(word.word) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(hSpace(10))
        actionRow.addView(pillButton(if (word.favorite) "★ 已收藏" else "☆ 收藏", false) {
            db.setFavorite(word.id, !word.favorite)
            renderPage()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(actionRow)
        parent.addView(vSpace(12))
        parent.addView(pillButton("下一个单词  →", true) {
            advanceBrowseWord()
            renderPage()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(vSpace(10))
        parent.addView(text("∞  无限浏览  ·  累计浏览 ${browseCount} 词  ·  不计入每日背词进度", 11f, secondary, false, Gravity.CENTER))
        parent.addView(vSpace(5))
        parent.addView(text("${book.name}  ·  重要词优先，单轮不重复", 10f, secondary, false, Gravity.CENTER))
    }

'''
s = s[:start] + new_browse + s[end:]

# Increase the deliberate study card's visual weight without changing its rating workflow.
s = s.replace('        val flashCard = card(20)\n', '        val flashCard = card(22).apply { minimumHeight = dp(500) }\n', 1)

# Book changes reset both independent queues so IDs from another book are never reused.
s = s.replace('''                    queue.clear(); queueIndex = 0; saveQueue()
                    renderPage()''', '''                    queue.clear(); queueIndex = 0; saveQueue()
                    resetBrowseState()
                    renderPage()''')

# Update Settings' about/version notes.
s = s.replace('''        about.addView(text("版本 1.0.0 · 离线优先\\n本地 SQLite 保存词书、收藏、学习反馈与复习历史。\\n当前内置词书为体验词包，词频权重是初版参考值，不是历年高考真题频率统计。", 11f, secondary))''', '''        about.addView(text("版本 1.2.0 · 离线优先\\n随刷与每日背词完全分离：随刷不记录熟悉度、不改变复习进度；背词保留每日目标与间隔复习。\\n重要度采用课标层级与第三方频率参考分层展示；第三方统计不等同于官方历年高考完整词频。", 11f, secondary))''')

# Insert browse queue persistence helpers immediately before the study queue helpers.
marker = '    private fun ensureQueue(forceNew: Boolean) {'
helpers = '''    private fun browseCurrentWord(): VocabularyWord? {
        if (browseQueue.isEmpty() || browseIndex >= browseQueue.size) ensureBrowseQueue(forceNew = true)
        val id = browseQueue.getOrNull(browseIndex) ?: return null
        return db.getWord(id)
    }

    private fun ensureBrowseQueue(forceNew: Boolean) {
        if (!forceNew && browseQueue.isNotEmpty() && browseIndex < browseQueue.size) return
        val all = db.getWords(currentBookId)
        browseQueue = WeightedScheduler.createBrowseQueue(all, browseRecentIds).toMutableList()
        browseIndex = 0
        saveBrowseState()
    }

    private fun advanceBrowseWord() {
        val currentId = browseQueue.getOrNull(browseIndex)
        if (currentId != null) {
            browseRecentIds.remove(currentId)
            browseRecentIds.add(0, currentId)
            if (browseRecentIds.size > 30) browseRecentIds = browseRecentIds.take(30).toMutableList()
            browseCount += 1
            browseIndex += 1
        }
        lastAutoSpokenWordId = -1L
        if (browseIndex >= browseQueue.size) {
            browseQueue.clear()
            browseIndex = 0
        }
        saveBrowseState()
    }

    private fun resetBrowseState() {
        browseQueue.clear()
        browseIndex = 0
        browseRecentIds.clear()
        browseCount = 0
        saveBrowseState()
    }

    private fun saveBrowseState() {
        prefs.edit()
            .putString(KEY_BROWSE_BOOK, currentBookId)
            .putString(KEY_BROWSE_QUEUE, browseQueue.joinToString(","))
            .putInt(KEY_BROWSE_INDEX, browseIndex)
            .putString(KEY_BROWSE_RECENT, browseRecentIds.joinToString(","))
            .putInt(KEY_BROWSE_COUNT, browseCount)
            .apply()
    }

    private fun loadBrowseState() {
        val savedBook = prefs.getString(KEY_BROWSE_BOOK, currentBookId)
        if (savedBook != currentBookId) {
            resetBrowseState()
            return
        }
        browseQueue = (prefs.getString(KEY_BROWSE_QUEUE, "") ?: "").split(",")
            .mapNotNull { it.toLongOrNull() }.toMutableList()
        browseIndex = prefs.getInt(KEY_BROWSE_INDEX, 0).coerceIn(0, browseQueue.size)
        browseRecentIds = (prefs.getString(KEY_BROWSE_RECENT, "") ?: "").split(",")
            .mapNotNull { it.toLongOrNull() }.take(30).toMutableList()
        browseCount = prefs.getInt(KEY_BROWSE_COUNT, 0).coerceAtLeast(0)
    }

'''
assert marker in s
s = s.replace(marker, helpers + marker, 1)

# Navigation constants: first tab is now the independent Quick Browse feature.
s = s.replace('        private const val TAB_HOME = 0\n', '        private const val TAB_BROWSE = 0\n')
s = s.replace('        private const val KEY_QUEUE_INDEX = "study_queue_index"\n', '''        private const val KEY_QUEUE_INDEX = "study_queue_index"
        private const val KEY_BROWSE_BOOK = "browse_book"
        private const val KEY_BROWSE_QUEUE = "browse_queue"
        private const val KEY_BROWSE_INDEX = "browse_queue_index"
        private const val KEY_BROWSE_RECENT = "browse_recent_ids"
        private const val KEY_BROWSE_COUNT = "browse_count"
''')

s = s.replace('TAB_HOME', 'TAB_BROWSE')
MAIN.write_text(s, encoding='utf-8')
# Make the vocabulary card itself ease into view when the word changes.
s = MAIN.read_text(encoding='utf-8')
old_card = '        parent.addView(flashCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))\n        parent.addView(vSpace(13))'
new_card = '''        parent.addView(flashCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        flashCard.alpha = 0f
        flashCard.translationY = dp(10).toFloat()
        flashCard.animate().alpha(1f).translationY(0f).setDuration(230).setInterpolator(DecelerateInterpolator()).start()
        parent.addView(vSpace(13))'''
assert old_card in s, 'Quick Browse card insertion point not found'
s = s.replace(old_card, new_card, 1)
s = s.replace('返回学习首页', '返回随刷')
MAIN.write_text(s, encoding='utf-8')

# Keep the scheduler smoke test aligned with the 25-word cooldown for this 100-word fixture.
(ROOT / 'core-tests/SchedulerSmokeTest.kt').write_text('''import com.words.app.VocabularyWord
import com.words.app.WeightedScheduler
import kotlin.random.Random

fun main() {
    val words = (1L..100L).map { id ->
        VocabularyWord(
            id = id, bookId = "test", word = "word$id", phonetic = "", partOfSpeech = "n.",
            meaning = "meaning", example = "", exampleZh = "", importance = if (id <= 10) 5 else 1,
            corpusFrequency = if (id <= 10) 1000 else 10,
            frequencyRank = if (id <= 10) "high" else "low"
        )
    }
    val recent = (1L..30L).toList()
    val study = WeightedScheduler.createQueue(words, recent, 30, now = 1_800_000_000_000L, random = Random(42))
    check(study.size == 30 && study.toSet().size == 30)
    check(study.none { it in recent.take(25) })

    var high = 0
    var low = 0
    repeat(10_000) { seed ->
        val sample = WeightedScheduler.createQueue(words, emptyList(), 1, now = 1_800_000_000_000L, random = Random(seed)).single()
        if (sample <= 10) high++ else low++
    }
    check(high > low) { "high-priority/high-frequency words should be favored" }

    val browse = WeightedScheduler.createBrowseQueue(words, recent, Random(42))
    check(browse.size == words.size && browse.toSet().size == words.size)
    check(browse.take(words.size - 25).none { it in recent.take(25) })
    check(browse.takeLast(25).toSet() == recent.take(25).toSet())
    println("PASS: separated study/browse queues, no in-cycle repeats, cooldown, and weighted priority ($high vs $low).")
}
''', encoding='utf-8')

# --- Launcher icon: open book glyph on a deep-indigo gradient ---
(ROOT / 'app/src/main/res/drawable/ic_words_background.xml').write_text('''<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:angle="135" android:startColor="#6679E8" android:endColor="#3D50B7" android:type="linear" />
</shape>
''', encoding='utf-8')
(ROOT / 'app/src/main/res/drawable/ic_words_foreground.xml').write_text('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFFFF" android:strokeColor="#FFFFFFFF" android:strokeWidth="1.5"
        android:strokeLineJoin="round"
        android:pathData="M19,29 C29,25 39,27 49,33 L49,80 C39,74 29,73 19,78 Z" />
    <path android:fillColor="#F4F6FF" android:strokeColor="#FFFFFFFF" android:strokeWidth="1.5"
        android:strokeLineJoin="round"
        android:pathData="M49,33 C59,27 69,25 89,29 L89,78 C69,73 59,74 49,80 Z" />
    <path android:fillColor="#5269D9" android:pathData="M48,33 L51,33 L51,81 L48,81 Z" />
    <path android:fillColor="#B9C4FF" android:pathData="M27,40 C34,38 40,40 44,43 L44,47 C38,44 33,43 27,45 Z" />
    <path android:fillColor="#B9C4FF" android:pathData="M27,52 C34,50 40,52 44,55 L44,59 C38,56 33,55 27,57 Z" />
    <path android:fillColor="#B9C4FF" android:pathData="M56,43 C64,39 73,38 81,40 L81,45 C72,43 64,44 56,47 Z" />
    <path android:fillColor="#B9C4FF" android:pathData="M56,55 C64,51 73,50 81,52 L81,57 C72,55 64,56 56,59 Z" />
    <path android:fillColor="#DDE2FF" android:pathData="M49,22 C52,16 56,16 59,22 C56,21 52,21 49,22 Z" />
</vector>
''', encoding='utf-8')
(ROOT / 'app/src/main/res/drawable/ic_launcher_monochrome.xml').write_text('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFFFF" android:pathData="M19,29 C29,25 39,27 49,33 L49,80 C39,74 29,73 19,78 Z" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M49,33 C59,27 69,25 89,29 L89,78 C69,73 59,74 49,80 Z" />
</vector>
''', encoding='utf-8')
(ROOT / 'app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml').write_text('''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_words_background" />
    <foreground android:drawable="@drawable/ic_words_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
''', encoding='utf-8')


(ROOT / 'app/src/main/res/mipmap-anydpi').mkdir(parents=True, exist_ok=True)
(ROOT / 'app/src/main/res/mipmap-anydpi/ic_launcher.xml').write_text('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp" android:height="48dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#6679E8" android:pathData="M0,0h108v108h-108z" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M19,29 C29,25 39,27 49,33 L49,80 C39,74 29,73 19,78 Z" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M49,33 C59,27 69,25 89,29 L89,78 C69,73 59,74 49,80 Z" />
</vector>
''', encoding='utf-8')

# Version increment.
gradle = ROOT / 'app/build.gradle.kts'
g = gradle.read_text(encoding='utf-8')
g = g.replace('versionCode = 2', 'versionCode = 3').replace('versionName = "1.1.0"', 'versionName = "1.2.0"')
gradle.write_text(g, encoding='utf-8')

assert 'TAB_HOME' not in s, 'Legacy home tab reference remains'
assert 'renderBrowse(content)' in s, 'Quick Browse route missing'
assert 'setDuration(190)' in s, 'Page transition missing'
print(f'Applied Quick Browse, scheduler, database corpus metadata, icon and animation upgrade to {ROOT}')