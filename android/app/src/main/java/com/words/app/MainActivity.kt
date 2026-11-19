package com.words.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var db: WordsDatabase
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var ttsInitializationComplete = false
    private var ttsStatusMessage = "正在检查系统英语语音…"
    private var activeTtsLocale: Locale = Locale.US
    private var showingFavorites = false
    private lateinit var pageHost: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var browseActionBar: LinearLayout
    private var selectedTab = TAB_BROWSE
    private var currentBookId: String = WordsDatabase.BUILTIN_BOOK_ID
    private var queue: MutableList<Long> = mutableListOf()
    private var queueIndex = 0
    private var browseQueue: MutableList<Long> = mutableListOf()
    private var browseIndex = 0
    private var browseRecentIds: MutableList<Long> = mutableListOf()
    private var browseCount = 0
    private var revealedWord = false
    private var revealedMeaning = false
    private var searchQuery = ""
    private var lastAutoSpokenWordId: Long = -1L

    private val bg = 0xFFF7F8FA.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val ink = 0xFF191B20.toInt()
    private val secondary = 0xFF858A94.toInt()
    private val line = 0xFFE7E9EE.toInt()
    private val accent = 0xFF5269D9.toInt()
    private val softAccent = 0xFFEFF1FF.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        db = WordsDatabase(this)
        db.seedBuiltInPack(this)
        prefs = getSharedPreferences("words_preferences", MODE_PRIVATE)
        currentBookId = prefs.getString(KEY_BOOK, WordsDatabase.BUILTIN_BOOK_ID) ?: WordsDatabase.BUILTIN_BOOK_ID
        // Move users of the original 115-word demo pack to the full curriculum pack.
        if (currentBookId == "gaokao_core_demo" || db.installedBooks().none { it.id == currentBookId }) {
            currentBookId = WordsDatabase.BUILTIN_BOOK_ID
        }
        prefs.edit().putString(KEY_BOOK, currentBookId).apply()
        loadQueueFromPreferences()
        loadBrowseState()
        tts = TextToSpeech(this, this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        pageHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pageHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // Keep the main Quick Browse action anchored above the navigation bar.
        browseActionBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(7), dp(22), dp(7))
            setBackgroundColor(bg)
            visibility = View.GONE
        }
        root.addView(browseActionBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)))
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(7))
            background = rounded(white, 0, 0, topOnly = true)
            elevation = dp(8).toFloat()
        }
        root.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)))
        setContentView(root)
        renderPage()
    }

    override fun onInit(status: Int) {
        ttsInitializationComplete = true
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            ttsStatusMessage = "系统语音引擎初始化失败"
            return
        }
        configureEnglishTts()
        if (::pageHost.isInitialized && selectedTab == TAB_SETTINGS) renderPage()
    }

    override fun onResume() {
        super.onResume()
        // Re-check after returning from the Android system voice-data installer.
        if (::tts.isInitialized && ttsInitializationComplete) {
            configureEnglishTts()
            if (selectedTab == TAB_SETTINGS) renderPage()
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, strokeColor: Int = 0, radius: Int = 18, topOnly: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadii = if (topOnly) floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
            else FloatArray(8) { dp(radius).toFloat() }
            if (strokeColor != 0) setStroke(dp(1), strokeColor)
        }

    private fun vSpace(height: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }

    private fun hSpace(width: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }

    private fun column(padding: Int = 0): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (padding > 0) setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun text(
        value: String,
        size: Float = 14f,
        color: Int = ink,
        bold: Boolean = false,
        gravity: Int = Gravity.START
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        this.gravity = gravity
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1.0f)
    }

    private fun card(padding: Int = 18): LinearLayout = column(padding).apply {
        background = rounded(white, line, 20)
    }

    private fun pillButton(label: String, primary: Boolean = false, onClick: () -> Unit): TextView = text(
        label, 14f, if (primary) white else ink, true, Gravity.CENTER
    ).apply {
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = rounded(if (primary) accent else white, if (primary) accent else line, 14)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun quietButton(label: String, onClick: () -> Unit): TextView = text(label, 13f, secondary, true, Gravity.CENTER).apply {
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun addSectionTitle(parent: LinearLayout, title: String, subtitle: String? = null) {
        parent.addView(text(title, 21f, ink, true))
        if (!subtitle.isNullOrBlank()) {
            parent.addView(vSpace(5))
            parent.addView(text(subtitle, 12f, secondary))
        }
    }

    private fun renderPage() {
        pageHost.animate().cancel()
        pageHost.alpha = 0.92f
        pageHost.removeAllViews()
        val scroll = ScrollView(this).apply { clipToPadding = false; isFillViewport = true }
        val content = column(22).apply { setPadding(dp(22), dp(22), dp(22), dp(30)) }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pageHost.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (showingFavorites) {
            renderFavorites(content)
        } else {
            when (selectedTab) {
                TAB_BROWSE -> renderBrowse(content)
                TAB_STUDY -> renderStudy(content)
                TAB_BOOKS -> renderBooks(content)
                TAB_STATS -> renderStats(content)
                TAB_SETTINGS -> renderSettings(content)
            }
        }
        renderBrowseActionBar()
        renderBottomBar()
        pageHost.animate().alpha(1f).setDuration(190).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun renderBrowseActionBar() {
        browseActionBar.removeAllViews()
        val canAdvance = selectedTab == TAB_BROWSE && browseQueue.getOrNull(browseIndex) != null
        browseActionBar.visibility = if (canAdvance) View.VISIBLE else View.GONE
        if (!canAdvance) return

        browseActionBar.addView(
            pillButton("下一个单词  →", true) {
                advanceBrowseWord()
                renderPage()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun renderBottomBar() {
        bottomBar.removeAllViews()
        val tabs = listOf(
            TAB_BROWSE to "随刷",
            TAB_STUDY to "背词",
            TAB_BOOKS to "词库",
            TAB_STATS to "统计",
            TAB_SETTINGS to "我的"
        )
        tabs.forEach { (tab, label) ->
            val active = selectedTab == tab
            val item = column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(5), dp(4), dp(4))
                if (active) background = rounded(softAccent, 0, 13)
                isClickable = true
                setOnClickListener {
                    showingFavorites = false
                    selectedTab = tab
                    renderPage()
                }
            }
            item.addView(text(
                when (tab) { TAB_BROWSE -> "∞"; TAB_STUDY -> "▱"; TAB_BOOKS -> "▤"; TAB_STATS -> "▥"; else -> "○" },
                20f, if (active) accent else secondary, true, Gravity.CENTER
            ))
            item.addView(vSpace(3))
            item.addView(text(label, 10f, if (active) accent else secondary, active, Gravity.CENTER))
            bottomBar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun renderBrowse(parent: LinearLayout) {
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
            minimumHeight = dp(430)
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
        flashCard.alpha = 0f
        flashCard.translationY = dp(10).toFloat()
        flashCard.animate().alpha(1f).translationY(0f).setDuration(230).setInterpolator(DecelerateInterpolator()).start()
        parent.addView(vSpace(13))

        val actionRow = row()
        actionRow.addView(pillButton("单词发音", false) { speak(word.word) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(hSpace(7))
        actionRow.addView(pillButton("朗读例句", false) {
            if (word.example.isNotBlank()) speak(word.example) else Toast.makeText(this, "这条词目暂时没有英文例句", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(hSpace(7))
        actionRow.addView(pillButton(if (word.favorite) "★ 已收藏" else "☆ 收藏", false) {
            db.setFavorite(word.id, !word.favorite)
            renderPage()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(actionRow)
        parent.addView(vSpace(10))
        parent.addView(text("∞  无限浏览  ·  累计浏览 ${browseCount} 词  ·  不计入每日背词进度", 11f, secondary, false, Gravity.CENTER))
        parent.addView(vSpace(5))
        parent.addView(text("${book.name}  ·  重要词优先，单轮不重复", 10f, secondary, false, Gravity.CENTER))
    }

    private fun metricCard(value: String, caption: String): LinearLayout = card(14).apply {
        addView(text(value, 22f, ink, true))
        addView(vSpace(6))
        addView(text(caption, 11f, secondary))
    }

    private fun renderStudy(parent: LinearLayout) {
        if (queue.isEmpty()) ensureQueue(forceNew = true)
        val book = activeBook()
        parent.addView(row().apply {
            addView(quietButton("‹  返回", onClick = { selectedTab = TAB_BROWSE; renderPage() }))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(text("DAILY SESSION", 10f, secondary, true))
        })
        parent.addView(vSpace(15))
        if (queue.isEmpty() || queueIndex >= queue.size) {
            renderSessionFinished(parent, book)
            return
        }
        val goal = queue.size
        val progress = (queueIndex.toFloat() / goal).coerceIn(0f, 1f)
        val progressHeader = row()
        progressHeader.addView(text("今日学习", 12f, secondary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        progressHeader.addView(text("${queueIndex + 1} / $goal", 12f, ink, true))
        parent.addView(progressHeader)
        parent.addView(vSpace(9))
        val progressTrack = LinearLayout(this).apply {
            background = rounded(line, 0, 6)
            clipToOutline = true
        }
        val progressFill = View(this).apply { background = rounded(accent, 0, 6) }
        progressTrack.addView(progressFill, LinearLayout.LayoutParams(0, dp(5)))
        parent.addView(progressTrack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)))
        progressTrack.post {
            val params = progressFill.layoutParams as LinearLayout.LayoutParams
            params.width = (progressTrack.width * progress).toInt()
            progressFill.layoutParams = params
        }
        parent.addView(vSpace(20))

        val word = db.getWord(queue[queueIndex])
        if (word == null) {
            advanceWithoutRating(); renderPage(); return
        }
        if (!prefs.getBoolean(KEY_AUTO_COVER, true)) {
            revealedWord = true
            revealedMeaning = true
        }

        // The reveal-choice state needs less vertical space; let the card grow only when answers/examples are shown.
        val flashCard = card(22).apply { minimumHeight = dp(360) }
        val head = row()
        head.addView(text("VOCABULARY  ·  ${queueIndex + 1}" , 10f, secondary, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val fav = text(if (word.favorite) "★" else "☆", 22f, if (word.favorite) accent else secondary, false, Gravity.CENTER).apply {
            setPadding(dp(8), dp(2), dp(2), dp(2))
            setOnClickListener { db.setFavorite(word.id, !word.favorite); renderPage() }
        }
        head.addView(fav)
        flashCard.addView(head)
        flashCard.addView(vSpace(24))

        if (!revealedWord && !revealedMeaning) {
            flashCard.addView(text("准备好了吗？", 22f, ink, true, Gravity.CENTER).apply { gravity = Gravity.CENTER })
            flashCard.addView(vSpace(8))
            flashCard.addView(text("先在脑海中回忆，再选择查看哪一面。", 12f, secondary, false, Gravity.CENTER))
            flashCard.addView(vSpace(27))
            flashCard.addView(text("选择先查看的内容", 11f, secondary, false, Gravity.CENTER))
            flashCard.addView(vSpace(12))
            val revealChoices = row()
            val meaningFirst = (prefs.getString(KEY_FIRST_SIDE, "word") ?: "word") == "meaning"
            val firstLabel = if (meaningFirst) "查看释义" else "查看单词"
            val secondLabel = if (meaningFirst) "查看单词" else "查看释义"
            revealChoices.addView(pillButton(firstLabel, true) {
                if (meaningFirst) revealedMeaning = true else revealedWord = true
                renderPage()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            revealChoices.addView(hSpace(10))
            revealChoices.addView(pillButton(secondLabel, false) {
                if (meaningFirst) revealedWord = true else revealedMeaning = true
                renderPage()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            flashCard.addView(revealChoices)
        } else {
            if (revealedWord) {
                flashCard.addView(text(word.word, 31f, ink, true, Gravity.CENTER))
                flashCard.addView(vSpace(10))
                flashCard.addView(text(word.phonetic, 13f, secondary, false, Gravity.CENTER))
                flashCard.addView(vSpace(7))
                flashCard.addView(text(word.partOfSpeech, 12f, secondary, false, Gravity.CENTER))
                flashCard.addView(vSpace(10))
                if (prefs.getBoolean(KEY_AUTO_SPEAK, false) && lastAutoSpokenWordId != word.id) {
                    lastAutoSpokenWordId = word.id
                    speak(word.word)
                }
                flashCard.addView(quietButton("播放发音  ·  音频由系统语音提供", { speak(word.word) }))
            }
            if (revealedWord && revealedMeaning) flashCard.addView(vSpace(13))
            if (revealedMeaning) {
                if (revealedWord) flashCard.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                flashCard.addView(vSpace(13))
                flashCard.addView(text(word.meaning, 20f, ink, true, Gravity.CENTER))
            }
            if (!revealedWord || !revealedMeaning) {
                flashCard.addView(vSpace(22))
                flashCard.addView(pillButton(if (revealedWord) "揭晓中文释义" else "揭晓英文单词", true) {
                    revealedWord = true
                    revealedMeaning = true
                    renderPage()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            if (revealedWord && revealedMeaning) {
                if (word.example.isNotBlank()) {
                    flashCard.addView(vSpace(18))
                    flashCard.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                    flashCard.addView(vSpace(14))
                    flashCard.addView(text("例句", 10f, secondary, true))
                    flashCard.addView(vSpace(6))
                    flashCard.addView(text(word.example, 14f, ink))
                    if (word.exampleZh.isNotBlank()) {
                        flashCard.addView(vSpace(5))
                        flashCard.addView(text(word.exampleZh, 12f, secondary))
                    }
                    flashCard.addView(vSpace(8))
                    flashCard.addView(quietButton("朗读英文例句  ·  系统语音", { speak(word.example) }))
                }
                flashCard.addView(vSpace(18))
                flashCard.addView(text("你对这个词的掌握程度？", 11f, secondary, false, Gravity.CENTER))
                flashCard.addView(vSpace(11))
                val ratings = row()
                ratings.addView(pillButton("不认识", false) { rateAndNext(word.id, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                ratings.addView(hSpace(7))
                ratings.addView(pillButton("模糊", false) { rateAndNext(word.id, 1) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                ratings.addView(hSpace(7))
                ratings.addView(pillButton("认识", true) { rateAndNext(word.id, 2) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                flashCard.addView(ratings)
            }
        }
        flashCard.addView(vSpace(8))
        parent.addView(flashCard)
        parent.addView(vSpace(14))
        parent.addView(text("${book.name}  ·  权重抽取 / 队列内不重复", 11f, secondary, false, Gravity.CENTER))
        parent.addView(vSpace(6))
        parent.addView(quietButton("跳过此词", { advanceWithoutRating(); renderPage() }))
    }

    private fun renderSessionFinished(parent: LinearLayout, book: VocabularyBook) {
        parent.addView(vSpace(65))
        parent.addView(text("本轮完成。", 29f, ink, true, Gravity.CENTER))
        parent.addView(vSpace(10))
        parent.addView(text("你已经完成本轮 ${queue.size} 个单词。\n休息一下，或开始下一轮。", 14f, secondary, false, Gravity.CENTER))
        parent.addView(vSpace(25))
        val finishCard = card(20)
        finishCard.addView(text("本轮学习记录", 13f, secondary, true))
        finishCard.addView(vSpace(10))
        finishCard.addView(text("${queue.size} 张卡片已完成或跳过", 19f, ink, true))
        finishCard.addView(vSpace(7))
        finishCard.addView(text("记忆反馈已保存在本机。", 12f, secondary))
        parent.addView(finishCard)
        parent.addView(vSpace(18))
        parent.addView(pillButton("再来一轮", true) {
            ensureQueue(forceNew = true)
            renderPage()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(vSpace(9))
        parent.addView(pillButton("返回随刷", false) { selectedTab = TAB_BROWSE; renderPage() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun renderBooks(parent: LinearLayout) {
        addSectionTitle(parent, "词库", "管理已保存在本机的离线词书")
        parent.addView(vSpace(18))
        parent.addView(pillButton("导入离线 JSON 词书包", true) { openPackPicker() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(vSpace(9))
        parent.addView(text("可从设备文件中选择词书包。导入后无需联网即可学习。", 11f, secondary))
        parent.addView(vSpace(21))
        db.installedBooks().forEach { book ->
            val item = card(17)
            val head = row()
            head.addView(text(book.name, 16f, ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(text(if (book.id == currentBookId) "正在使用" else "已安装", 10f, if (book.id == currentBookId) accent else secondary, true))
            item.addView(head)
            item.addView(vSpace(7))
            item.addView(text("${book.wordCount} 个单词  ·  版本 ${book.version}", 11f, secondary))
            if (book.description.isNotBlank()) {
                item.addView(vSpace(8))
                item.addView(text(book.description, 12f, secondary))
            }
            if (book.id != currentBookId) {
                item.addView(vSpace(12))
                item.addView(pillButton("设为当前词书", false) {
                    currentBookId = book.id
                    prefs.edit().putString(KEY_BOOK, book.id).apply()
                    queue.clear(); queueIndex = 0; saveQueue()
                    resetBrowseState()
                    renderPage()
                })
            }
            parent.addView(item)
            parent.addView(vSpace(10))
        }
        parent.addView(vSpace(10))
        val formatCard = card(16)
        formatCard.addView(text("词书包格式", 14f, ink, true))
        formatCard.addView(vSpace(7))
        formatCard.addView(text("JSON 文件需包含 id、name、version 和 words。每个单词至少包含 word、meaning，可选 phonetic、pos、example、example_zh、importance（1–5）。", 11f, secondary))
        parent.addView(formatCard)
    }

    private fun renderStats(parent: LinearLayout) {
        addSectionTitle(parent, "学习统计", "数据仅保存在这台设备上")
        parent.addView(vSpace(18))
        val words = db.totalWordCount(currentBookId)
        val known = db.knownCount(currentBookId)
        val weak = db.weakCount(currentBookId)
        val fav = db.favoriteCount(currentBookId)
        val today = db.dailyReviewedCount()
        val grid = row()
        grid.addView(metricCard("$today", "今日复习"), LinearLayout.LayoutParams(0, dp(94), 1f))
        grid.addView(hSpace(9))
        grid.addView(metricCard("$words", "词书总量"), LinearLayout.LayoutParams(0, dp(94), 1f))
        parent.addView(grid)
        parent.addView(vSpace(9))
        val grid2 = row()
        grid2.addView(metricCard("$known", "已熟悉"), LinearLayout.LayoutParams(0, dp(94), 1f))
        grid2.addView(hSpace(9))
        grid2.addView(metricCard("$weak", "需要加强"), LinearLayout.LayoutParams(0, dp(94), 1f))
        parent.addView(grid2)
        parent.addView(vSpace(18))
        val breakdown = card(18)
        breakdown.addView(text("掌握情况", 15f, ink, true))
        breakdown.addView(vSpace(13))
        val notSeen = (words - known - weak).coerceAtLeast(0)
        val denominator = words.coerceAtLeast(1)
        addStatBar(breakdown, "已熟悉", known, denominator, accent)
        addStatBar(breakdown, "模糊 / 不认识", weak, denominator, 0xFF9AA2B1.toInt())
        addStatBar(breakdown, "尚未记录", notSeen, denominator, 0xFFE5E7EB.toInt())
        parent.addView(breakdown)
        parent.addView(vSpace(15))
        val more = card(16)
        more.addView(text("其他记录", 14f, ink, true))
        more.addView(vSpace(9))
        more.addView(text("累计反馈次数：${db.totalReviewedCount()}", 12f, secondary))
        more.addView(vSpace(5))
        more.addView(text("收藏单词：$fav", 12f, secondary))
        more.addView(vSpace(11))
        more.addView(pillButton("查看收藏单词  →", true) {
            showingFavorites = true
            renderPage()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(more)
        parent.addView(vSpace(12))
        parent.addView(text("说明：当前统计反映本机记录，不会自动上传或跨设备同步。", 11f, secondary))
    }

    private fun addStatBar(parent: LinearLayout, label: String, count: Int, total: Int, color: Int) {
        val header = row()
        header.addView(text(label, 11f, secondary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(text("$count", 11f, ink, true))
        parent.addView(header)
        parent.addView(vSpace(6))
        val holder = LinearLayout(this).apply { background = rounded(line, 0, 6) }
        val fill = View(this).apply { background = rounded(color, 0, 6) }
        holder.addView(fill, LinearLayout.LayoutParams(0, dp(6)))
        parent.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
        holder.post {
            if (holder.width > 0) {
                val lp = fill.layoutParams as LinearLayout.LayoutParams
                lp.width = (holder.width * count.toFloat() / total).toInt()
                fill.layoutParams = lp
            }
        }
        parent.addView(vSpace(13))
    }

    private fun renderSettings(parent: LinearLayout) {
        addSectionTitle(parent, "设置", "按自己的记忆习惯调整学习体验")
        parent.addView(vSpace(18))
        val coverCard = card(17)
        coverCard.addView(text("卡片显示", 15f, ink, true))
        coverCard.addView(vSpace(12))
        addSwitchRow(coverCard, "自动遮盖单词与释义", "开启后，卡片初始状态隐藏两面内容。", KEY_AUTO_COVER, true)
        addDivider(coverCard)
        addSwitchRow(coverCard, "自动播放英文发音", "揭晓英文时播放系统语音；是否离线取决于设备语音引擎。", KEY_AUTO_SPEAK, false)
        parent.addView(coverCard)
        parent.addView(vSpace(12))

        val pronunciationCard = card(17)
        pronunciationCard.addView(text("系统英语发音", 15f, ink, true))
        pronunciationCard.addView(vSpace(7))
        pronunciationCard.addView(text(ttsStatusMessage, 11f, if (ttsReady) accent else secondary))
        pronunciationCard.addView(vSpace(12))
        val voiceRow = row()
        val preferredTag = prefs.getString(KEY_TTS_LOCALE, "en-US") ?: "en-US"
        voiceRow.addView(pillButton("美式英语", preferredTag == "en-US") {
            prefs.edit().putString(KEY_TTS_LOCALE, "en-US").apply()
            if (ttsInitializationComplete) configureEnglishTts()
            renderPage()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        voiceRow.addView(hSpace(9))
        voiceRow.addView(pillButton("英式英语", preferredTag == "en-GB") {
            prefs.edit().putString(KEY_TTS_LOCALE, "en-GB").apply()
            if (ttsInitializationComplete) configureEnglishTts()
            renderPage()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pronunciationCard.addView(voiceRow)
        pronunciationCard.addView(vSpace(10))
        pronunciationCard.addView(pillButton("播放测试发音", false) {
            speak("The quick brown fox jumps over the lazy dog.")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pronunciationCard.addView(vSpace(8))
        pronunciationCard.addView(pillButton("安装 / 检查系统英语语音", false) {
            openTtsVoiceInstaller()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(pronunciationCard)
        parent.addView(vSpace(12))

        val favoriteEntry = card(17)
        favoriteEntry.addView(text("我的收藏", 15f, ink, true))
        favoriteEntry.addView(vSpace(6))
        favoriteEntry.addView(text("跨词书保存的单词都可以在这里查看和重新播放。", 11f, secondary))
        favoriteEntry.addView(vSpace(11))
        favoriteEntry.addView(pillButton("打开收藏夹（${db.favoriteCountAll()}）  →", true) {
            showingFavorites = true
            renderPage()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(favoriteEntry)
        parent.addView(vSpace(12))

        val directionCard = card(17)
        directionCard.addView(text("默认先查看哪一面", 15f, ink, true))
        directionCard.addView(vSpace(6))
        directionCard.addView(text("自动遮盖开启时，这只影响按钮的默认强调，不会自动揭晓答案。", 11f, secondary))
        directionCard.addView(vSpace(12))
        val sides = row()
        val first = prefs.getString(KEY_FIRST_SIDE, "word") ?: "word"
        sides.addView(pillButton("先看英文", first == "word") { prefs.edit().putString(KEY_FIRST_SIDE, "word").apply(); renderPage() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sides.addView(hSpace(9))
        sides.addView(pillButton("先看中文", first == "meaning") { prefs.edit().putString(KEY_FIRST_SIDE, "meaning").apply(); renderPage() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        directionCard.addView(sides)
        parent.addView(directionCard)
        parent.addView(vSpace(12))

        val goalCard = card(17)
        goalCard.addView(text("每日学习目标", 15f, ink, true))
        goalCard.addView(vSpace(7))
        val goalRow = row()
        val goal = prefs.getInt(KEY_GOAL, 30)
        goalRow.addView(text("每天计划学习", 12f, secondary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        goalRow.addView(pillButton("−", false) { prefs.edit().putInt(KEY_GOAL, (prefs.getInt(KEY_GOAL, 30) - 5).coerceAtLeast(5)).apply(); renderPage() })
        goalRow.addView(hSpace(10))
        goalRow.addView(text("$goal 词", 15f, ink, true))
        goalRow.addView(hSpace(10))
        goalRow.addView(pillButton("＋", false) { prefs.edit().putInt(KEY_GOAL, (prefs.getInt(KEY_GOAL, 30) + 5).coerceAtMost(100)).apply(); renderPage() })
        goalCard.addView(goalRow)
        parent.addView(goalCard)
        parent.addView(vSpace(12))

        val about = card(17)
        about.addView(text("关于 words", 15f, ink, true))
        about.addView(vSpace(8))
        about.addView(text("版本 1.3.0 · 离线优先\n随刷与每日背词完全分离：随刷不记录熟悉度、不改变复习进度；背词保留每日目标与间隔复习。\n重要度采用课标层级与第三方频率参考分层展示；第三方统计不等同于官方历年高考完整词频。", 11f, secondary))
        parent.addView(about)
    }

    private fun addSwitchRow(parent: LinearLayout, title: String, description: String, key: String, default: Boolean) {
        val r = row()
        val info = column()
        info.addView(text(title, 13f, ink, true))
        info.addView(vSpace(4))
        info.addView(text(description, 10f, secondary))
        r.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(this).apply {
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                if (key == KEY_AUTO_COVER) {
                    revealedWord = !checked
                    revealedMeaning = !checked
                    renderPage()
                }
            }
        }
        r.addView(toggle)
        parent.addView(r)
    }

    private fun addDivider(parent: LinearLayout) {
        parent.addView(vSpace(12))
        parent.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        parent.addView(vSpace(12))
    }

    private fun activeBook(): VocabularyBook = db.installedBooks().firstOrNull { it.id == currentBookId }
        ?: VocabularyBook(WordsDatabase.BUILTIN_BOOK_ID, "高中核心词汇", "", 1, db.totalWordCount(WordsDatabase.BUILTIN_BOOK_ID))

    private fun browseCurrentWord(): VocabularyWord? {
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

    private fun ensureQueue(forceNew: Boolean) {
        if (!forceNew && queue.isNotEmpty() && queueIndex < queue.size) return
        val all = db.getWords(currentBookId)
        val goal = prefs.getInt(KEY_GOAL, 30)
        queue = WeightedScheduler.createQueue(all, db.recentWordIds(currentBookId), goal).toMutableList()
        queueIndex = 0
        revealedWord = !prefs.getBoolean(KEY_AUTO_COVER, true)
        revealedMeaning = !prefs.getBoolean(KEY_AUTO_COVER, true)
        saveQueue()
    }

    private fun rateAndNext(wordId: Long, rating: Int) {
        db.saveReview(wordId, rating)
        advanceWithoutRating()
        renderPage()
    }

    private fun advanceWithoutRating() {
        queueIndex = (queueIndex + 1).coerceAtMost(queue.size)
        lastAutoSpokenWordId = -1L
        revealedWord = !prefs.getBoolean(KEY_AUTO_COVER, true)
        revealedMeaning = !prefs.getBoolean(KEY_AUTO_COVER, true)
        saveQueue()
    }

    private fun saveQueue() {
        prefs.edit().putString(KEY_QUEUE, queue.joinToString(","))
            .putInt(KEY_QUEUE_INDEX, queueIndex).apply()
    }

    private fun loadQueueFromPreferences() {
        queue = (prefs.getString(KEY_QUEUE, "") ?: "").split(",")
            .mapNotNull { it.toLongOrNull() }.toMutableList()
        queueIndex = prefs.getInt(KEY_QUEUE_INDEX, 0).coerceIn(0, queue.size)
    }

    private fun configureEnglishTts(): Boolean {
        if (!::tts.isInitialized || !ttsInitializationComplete) return false
        val preferredTag = prefs.getString(KEY_TTS_LOCALE, "en-US") ?: "en-US"
        val preferred = if (preferredTag == "en-GB") Locale.UK else Locale.US
        val fallback = if (preferred == Locale.US) Locale.UK else Locale.US
        val selected = listOf(preferred, fallback).firstOrNull { locale ->
            tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
        }

        if (selected == null) {
            ttsReady = false
            ttsStatusMessage = "没有检测到英语语音数据，请安装系统英语语音"
            return false
        }

        val languageResult = tts.setLanguage(selected)
        if (languageResult < TextToSpeech.LANG_AVAILABLE) {
            ttsReady = false
            ttsStatusMessage = "英语语音数据不可用，请在系统中安装"
            return false
        }

        activeTtsLocale = selected
        if (selected != preferred) {
            prefs.edit().putString(KEY_TTS_LOCALE, if (selected == Locale.UK) "en-GB" else "en-US").apply()
        }
        tts.setSpeechRate(0.88f)
        tts.setPitch(1.0f)
        ttsReady = true
        ttsStatusMessage = if (selected == Locale.UK) "系统英语语音已就绪 · 英式英语" else "系统英语语音已就绪 · 美式英语"
        return true
    }

    private fun openTtsVoiceInstaller() {
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "请到系统设置 → 文字转语音，安装英语语音数据", Toast.LENGTH_LONG).show()
        }
    }

    private fun speak(value: String) {
        val spokenText = value.trim()
        if (spokenText.isEmpty()) return
        if (!ttsReady) {
            Toast.makeText(this, "系统英语语音未就绪。请在“我的 → 系统英语发音”中安装语音数据。", Toast.LENGTH_LONG).show()
            return
        }
        val result = tts.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, "words_${System.nanoTime()}")
        if (result == TextToSpeech.ERROR) {
            Toast.makeText(this, "系统语音播放失败，请检查 TTS 引擎设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderFavorites(parent: LinearLayout) {
        parent.addView(row().apply {
            addView(quietButton("‹  返回", onClick = { showingFavorites = false; renderPage() }))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(text("SAVED WORDS", 10f, secondary, true))
        })
        parent.addView(vSpace(17))
        addSectionTitle(parent, "我的收藏", "收藏夹跨词书保存，退出应用后也不会丢失")
        parent.addView(vSpace(7))
        val favorites = db.getFavoriteWords()
        parent.addView(text("共收藏 ${favorites.size} 个单词", 11f, secondary))
        parent.addView(vSpace(16))

        if (favorites.isEmpty()) {
            val empty = card(22)
            empty.addView(text("还没有收藏单词", 17f, ink, true))
            empty.addView(vSpace(7))
            empty.addView(text("在随刷或背词卡片上点击 ☆ 收藏，单词就会出现在这里。", 12f, secondary))
            empty.addView(vSpace(12))
            empty.addView(pillButton("去随刷看看", true) {
                showingFavorites = false
                selectedTab = TAB_BROWSE
                renderPage()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            parent.addView(empty)
            return
        }

        val bookNames = db.installedBooks().associate { it.id to it.name }
        favorites.forEach { word ->
            val item = card(16)
            val head = row()
            head.addView(text(word.word, 20f, ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(pillButton("取消收藏", false) {
                db.setFavorite(word.id, false)
                renderPage()
            })
            item.addView(head)
            if (word.phonetic.isNotBlank()) {
                item.addView(vSpace(7))
                item.addView(text(word.phonetic, 12f, secondary))
            }
            if (word.partOfSpeech.isNotBlank()) {
                item.addView(vSpace(5))
                item.addView(text(word.partOfSpeech, 11f, secondary))
            }
            item.addView(vSpace(9))
            item.addView(text(word.meaning, 15f, ink, true))
            if (word.example.isNotBlank()) {
                item.addView(vSpace(9))
                item.addView(text(word.example, 13f, ink))
                if (word.exampleZh.isNotBlank()) {
                    item.addView(vSpace(4))
                    item.addView(text(word.exampleZh, 11f, secondary))
                }
            }
            item.addView(vSpace(10))
            item.addView(text(bookNames[word.bookId] ?: word.bookId, 10f, secondary))
            item.addView(vSpace(10))
            val actions = row()
            actions.addView(pillButton("单词发音", false) { speak(word.word) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (word.example.isNotBlank()) {
                actions.addView(hSpace(8))
                actions.addView(pillButton("朗读例句", false) { speak(word.example) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            item.addView(actions)
            parent.addView(item)
            parent.addView(vSpace(10))
        }
    }

    private fun openPackPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_IMPORT_PACK)
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT_PACK && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalArgumentException("无法读取所选文件")
                val pack = WordsDatabase.parsePack(raw)
                db.installPack(pack)
                Toast.makeText(this, "已导入：${pack.name}（${pack.words.size} 词）", Toast.LENGTH_LONG).show()
                selectedTab = TAB_BOOKS
                renderPage()
            } catch (e: Exception) {
                Toast.makeText(this, "导入失败：${e.message ?: "文件格式错误"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val TAB_BROWSE = 0
        private const val TAB_STUDY = 1
        private const val TAB_BOOKS = 2
        private const val TAB_STATS = 3
        private const val TAB_SETTINGS = 4
        private const val REQUEST_IMPORT_PACK = 4102
        private const val KEY_BOOK = "active_book"
        private const val KEY_QUEUE = "study_queue"
        private const val KEY_QUEUE_INDEX = "study_queue_index"
        private const val KEY_BROWSE_BOOK = "browse_book"
        private const val KEY_BROWSE_QUEUE = "browse_queue"
        private const val KEY_BROWSE_INDEX = "browse_queue_index"
        private const val KEY_BROWSE_RECENT = "browse_recent_ids"
        private const val KEY_BROWSE_COUNT = "browse_count"
        private const val KEY_AUTO_COVER = "auto_cover"
        private const val KEY_AUTO_SPEAK = "auto_speak"
        private const val KEY_FIRST_SIDE = "first_side"
        private const val KEY_GOAL = "daily_goal"
        private const val KEY_TTS_LOCALE = "tts_locale"
    }
}
