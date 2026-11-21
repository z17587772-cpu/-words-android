package com.words.app

import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.net.Uri
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.RelativeSizeSpan
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var db: WordsDatabase
    private lateinit var listeningRepository: ListeningRepository
    private lateinit var listeningPlaybackCoordinator: ListeningPlaybackCoordinator
    private var listeningMediaController: MediaController? = null
    private var listeningControllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var listeningStage = "home"
    private var discoveredDownloadedOnly = false
    private var discoveredFavoritesOnly = false
    private var discoveredSearchQuery = ""
    private var listeningStageTransitioning = false
    private var listeningDailyEpisodeId = ListeningRepository.DEMO_EPISODE_ID
    private var listeningEpisodeId = ListeningRepository.DEMO_EPISODE_ID
    private var listeningAttemptId = -1L
    private var listeningArchiveAssignmentDate: String? = null
    private var listeningQuestionIndex = 0
    private var listeningSelectedChoiceId: Long? = null
    private var listeningSubmitted = false
    private var listeningCurrentCorrect: Boolean? = null
    private var listeningCorrectChoiceId: Long? = null
    private var listeningCorrectCount = 0
    private val listeningPlaybackHandler = Handler(Looper.getMainLooper())
    private var listeningSegmentStopRunnable: Runnable? = null
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var ttsInitializationComplete = false
    private var ttsStatusMessage = "正在检查系统英语语音…"
    private var activeTtsLocale: Locale = Locale.US
    private var showingFavorites = false
    private var quizCurrentWordId = -1L
    private var quizSelectedOptionId: Long? = null
    private var quizAnswerCorrect: Boolean? = null
    private var quizShowExplanation = false
    private var browseCardFlipped = false
    private enum class BrowseMotionState { IDLE, DRAGGING, SETTLING, FLIPPING, TRANSITIONING }
    private var browseMotionState = BrowseMotionState.IDLE
    private val onlineDetails = mutableMapOf<String, DictionaryDetails>()
    private val onlineLoading = mutableSetOf<String>()
    private val onlineFailed = mutableSetOf<String>()
    private val translatedExamples = mutableMapOf<String, String>()
    private val translationLoading = mutableSetOf<String>()
    private val translationFailed = mutableSetOf<String>()
    private val definitionTranslationViews = mutableMapOf<String, MutableList<TextView>>()
    private var toneGenerator: ToneGenerator? = null
    private lateinit var pageHost: LinearLayout
    private var pageScrollView: ScrollView? = null
    private var pageContent: LinearLayout? = null
    private lateinit var bottomBar: LinearLayout
    private lateinit var browseActionBar: LinearLayout
    private var selectedTab = TAB_BROWSE
    private var lastRenderedTab = TAB_BROWSE
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
        // Draw the app background behind system bars, then inset app content so
        // the status bar, gesture handle and three-button navigation never overlap UI.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        } else {
            window.statusBarColor = bg
            window.navigationBarColor = bg
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        db = WordsDatabase(this)
        db.seedBuiltInPack(this)
        ListeningCatalogSync.schedule(this)
        ListeningDiscoveryScheduler.schedule(this)
        ListeningResourceAutomation.schedule(this)
        ListeningResourceReconciliationScheduler.schedule(this)
        listeningRepository = ListeningRepository(db)
        listeningPlaybackCoordinator = ListeningPlaybackCoordinator(listeningRepository)
        listeningRepository.ensureDemoSeeded()
        listeningDailyEpisodeId = listeningRepository.getTodayEpisodeId()
        listeningEpisodeId = listeningDailyEpisodeId
        prefs = getSharedPreferences("words_preferences", MODE_PRIVATE)
        currentBookId = prefs.getString(KEY_BOOK, WordsDatabase.BUILTIN_BOOK_ID) ?: WordsDatabase.BUILTIN_BOOK_ID
        // Move users of the original 115-word demo pack to the full curriculum pack.
        if (currentBookId == "gaokao_core_demo" || db.installedBooks().none { it.id == currentBookId }) {
            currentBookId = WordsDatabase.BUILTIN_BOOK_ID
        }
        prefs.edit().putString(KEY_BOOK, currentBookId).apply()
        loadQueueFromPreferences()
        loadBrowseState()
        restoreListeningAttemptState()
        tts = TextToSpeech(this, this)
        connectListeningPlayback()

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
            setPadding(dp(22), dp(4), dp(22), dp(4))
            setBackgroundColor(bg)
            visibility = View.GONE
        }
        root.addView(browseActionBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(7))
            background = rounded(white, 0, 0, topOnly = true)
            elevation = dp(8).toFloat()
        }
        root.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val topInset: Int
            val bottomInset: Int
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                topInset = bars.top
                bottomInset = bars.bottom
            } else {
                topInset = insets.systemWindowInsetTop
                bottomInset = insets.systemWindowInsetBottom
            }
            view.setPadding(0, topInset, 0, bottomInset)
            insets
        }
        root.requestApplyInsets()
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

    override fun onPause() {
        listeningMediaController?.let { listeningPlaybackCoordinator.checkpoint(it) }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Re-check after returning from the Android system voice-data installer.
        if (::tts.isInitialized && ttsInitializationComplete) {
            configureEnglishTts()
            if (selectedTab == TAB_SETTINGS) renderPage()
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (selectedTab == TAB_BROWSE && !showingFavorites && browseMotionState == BrowseMotionState.IDLE) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { swipeBrowse(-1); return true }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { swipeBrowse(1); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Android API 33; retained for consistent system Back handling")
    override fun onBackPressed() {
        if (selectedTab == TAB_LISTENING && listeningStage == "quiz") return
        super.onBackPressed()
    }

    override fun onDestroy() {
        listeningPlaybackCoordinator.release(listeningMediaController)
        if (::tts.isInitialized) tts.shutdown()
        toneGenerator?.release()
        toneGenerator = null
        listeningSegmentStopRunnable?.let { listeningPlaybackHandler.removeCallbacks(it) }
        listeningSegmentStopRunnable = null
        listeningControllerFuture?.let { MediaController.releaseFuture(it) }
        listeningControllerFuture = null
        listeningMediaController = null
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
        value: CharSequence,
        size: Float = 14f,
        color: Int = ink,
        bold: Boolean = false,
        gravity: Int = Gravity.START
    ): TextView = TextView(this).apply {
        text = value
        textSize = size + if (selectedTab == TAB_STUDY) 1.5f else 0f
        setTextColor(color)
        this.gravity = gravity
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1.0f)
    }

    private fun card(padding: Int = 18): LinearLayout = column(padding).apply {
        background = rounded(white, line, 20)
    }

    private fun pillButton(label: CharSequence, primary: Boolean = false, onClick: () -> Unit): TextView = text(
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

    /**
     * A short two-phase transition for listening-stage changes only. The
     * persistent navigation chrome stays still; ordinary answer selection
     * rerenders remain immediate to avoid unnecessary motion and flicker.
     */
    private fun transitionListeningStage(
        nextStage: String,
        beforeRender: () -> Unit = {}
    ) {
        if (listeningStageTransitioning || !::pageHost.isInitialized) return
        listeningStageTransitioning = true
        pageHost.animate().cancel()
        pageHost.animate()
            .alpha(0f)
            .translationY(-dp(4).toFloat())
            .setDuration(115L)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withLayer()
            .withEndAction {
                if (isFinishing || isDestroyed) {
                    listeningStageTransitioning = false
                    return@withEndAction
                }
                beforeRender()
                listeningStage = nextStage
                renderPage()
                pageHost.alpha = 0f
                pageHost.translationY = dp(7).toFloat()
                listeningStageTransitioning = true
                pageHost.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220L)
                    .setInterpolator(PathInterpolator(0.16f, 1f, 0.3f, 1f))
                    .withLayer()
                    .withEndAction { listeningStageTransitioning = false }
                    .start()
            }
            .start()
    }

    private fun renderPage() {
        val preserveStudyPosition = selectedTab == TAB_STUDY && quizShowExplanation
        val preserveListeningPosition = selectedTab == TAB_LISTENING &&
            lastRenderedTab == TAB_LISTENING && !listeningStageTransitioning
        val previousScrollY = if (preserveStudyPosition || preserveListeningPosition) {
            (pageHost.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        } else 0
        listeningStageTransitioning = false
        // Rebuild the page without fading the entire screen. The old global fade
        // combined with per-card entrance animations and caused a visible double flash.
        pageHost.animate().cancel()
        pageHost.alpha = 1f
        pageHost.translationY = 0f
        val compactBrowse = selectedTab == TAB_BROWSE && !showingFavorites
        // Keep the scrolling surface attached across refreshes to avoid a white flash.
        val scroll = pageScrollView ?: ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_ALWAYS
            isVerticalScrollBarEnabled = false
        }.also { created ->
            val content = column()
            created.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            pageScrollView = created
            pageContent = content
            pageHost.addView(created, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val content = pageContent ?: return
        content.removeAllViews()
        content.setPadding(dp(20), dp(12), dp(20), dp(if (compactBrowse) 8 else 30))
        if (!preserveStudyPosition && !preserveListeningPosition) scroll.scrollTo(0, 0)
        if (showingFavorites) {
            renderFavorites(content)
        } else {
            when (selectedTab) {
                TAB_BROWSE -> renderBrowse(content)
                TAB_STUDY -> renderStudy(content)
                TAB_BOOKS -> renderBooks(content)
                TAB_LISTENING -> renderListening(content)
                TAB_STATS -> renderStats(content)
                TAB_SETTINGS -> renderSettings(content)
            }
        }
        renderBrowseActionBar()
        renderBottomBar()
        if (compactBrowse) {
            scroll.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    view: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    scroll.removeOnLayoutChangeListener(this)
                    val measuredWidth = scroll.width.coerceAtLeast(1)
                    content.measure(
                        View.MeasureSpec.makeMeasureSpec(measuredWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    val overflow = (content.measuredHeight - scroll.height).coerceAtLeast(0)
                    if (overflow > 0) {
                        val stage = content.findViewWithTag<View>("browse_card_stage")
                        val stageParams = stage?.layoutParams as? LinearLayout.LayoutParams
                        if (stageParams != null) {
                            stageParams.height = (stageParams.height - overflow).coerceAtLeast(dp(200))
                            stage.layoutParams = stageParams
                            scroll.scrollTo(0, 0)
                        }
                    }
                }
            })
        }
        if (preserveStudyPosition || preserveListeningPosition) {
            scroll.post {
                val maxScroll = (content.height - scroll.height).coerceAtLeast(0)
                scroll.scrollTo(0, previousScrollY.coerceAtMost(maxScroll))
            }
        }
        lastRenderedTab = selectedTab
        pageHost.alpha = 1f
    }

    private fun renderBrowseActionBar() {
        browseActionBar.removeAllViews()
        val visible = selectedTab == TAB_BROWSE && !showingFavorites && browseQueue.getOrNull(browseIndex) != null
        browseActionBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        browseActionBar.addView(
            text("左右滑动切换单词", 10f, secondary, false, Gravity.CENTER),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

        private fun renderBottomBar() {
        val assessmentLocked = selectedTab == TAB_LISTENING && listeningStage == "quiz"
        bottomBar.visibility = if (assessmentLocked) View.GONE else View.VISIBLE
        bottomBar.layoutParams = bottomBar.layoutParams.apply { height = if (assessmentLocked) 0 else dp(66) }
        bottomBar.removeAllViews()
        if (assessmentLocked) return
        val tabs = listOf(
            TAB_BROWSE to "随刷",
            TAB_STUDY to "背词",
            TAB_LISTENING to "听力",
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
                when (tab) {
                    TAB_BROWSE -> "∞"
                    TAB_STUDY -> "▱"
                    TAB_LISTENING -> "♫"
                    TAB_BOOKS -> "▤"
                    TAB_STATS -> "▥"
                    else -> "○"
                },
                20f, if (active) accent else secondary, true, Gravity.CENTER
            ))
            item.addView(vSpace(3))
            item.addView(text(label, 9.5f, if (active) accent else secondary, active, Gravity.CENTER))
            bottomBar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }


    private fun renderBrowse(parent: LinearLayout) {
        val book = activeBook()
        val word = browseCurrentWord()

        val top = row()
        top.addView(
            text("WORDS  /  QUICK BROWSE", 10f, secondary, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        top.addView(quietButton("切换词书", { selectedTab = TAB_BOOKS; renderPage() }))
        parent.addView(top)
        parent.addView(vSpace(4))
        parent.addView(text("随刷", 25f, ink, true))
        parent.addView(vSpace(3))
        parent.addView(text("左滑下一张 · 右滑上一张 · 点击翻面", 12f, secondary))
        parent.addView(vSpace(6))

        if (word == null) {
            val empty = card(18)
            empty.addView(text("这本词书暂时没有单词", 17f, ink, true))
            empty.addView(vSpace(7))
            empty.addView(text("请在词库中切换或导入一份离线词书。", 12f, secondary))
            parent.addView(empty)
            return
        }

        val firstSide = prefs.getString(KEY_BROWSE_FIRST_SIDE, "en") ?: "en"
        val englishIsFront = firstSide != "zh"
        val screenHeightDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt()
        val cardHeightDp = (screenHeightDp - 445).coerceIn(255, 375)

        val darkCard = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val faceSurface = if (darkCard) 0xFF20232A.toInt() else white
        val faceInk = if (darkCard) 0xFFF1F2F5.toInt() else ink
        val faceMuted = if (darkCard) 0xFFB0B5C0.toInt() else secondary
        val faceBorder = if (darkCard) 0xFF383D48.toInt() else line

        fun frequencyText(cardWord: VocabularyWord): String {
            val level = when (cardWord.frequencyRank.lowercase()) {
                "high" -> "高频"
                "medium" -> "中频"
                "low" -> "低频"
                else -> ""
            }
            val count = if (cardWord.corpusFrequency >= 0) "综合语料 ${cardWord.corpusFrequency} 次" else ""
            return listOf(level, count).filter { it.isNotBlank() }.joinToString("  ·  ")
        }

        fun createCardFace(
            cardWord: VocabularyWord,
            englishSide: Boolean,
            active: Boolean,
            backLabel: String
        ): LinearLayout {
            val face = card(0).apply {
                gravity = Gravity.CENTER
                background = rounded(faceSurface, faceBorder, 25)
                elevation = 0f
            }
            val content = column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(13), dp(22), dp(13))
            }
            content.addView(
                text(
                    if (englishSide) "ENGLISH  ·  WORD" else "中文释义  ·  MEANING",
                    10f, faceMuted, true, Gravity.CENTER
                )
            )
            content.addView(vSpace(12))
            val mainText = text(
                if (englishSide) cardWord.word else formatMeaning(cardWord.meaning, cardWord.partOfSpeech),
                if (englishSide) 35.5f else 23.5f,
                faceInk, true, Gravity.CENTER
            ).apply {
                maxLines = if (englishSide) 2 else 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            content.addView(mainText, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (cardWord.phonetic.isNotBlank()) {
                content.addView(vSpace(10))
                content.addView(text("音标  ${cardWord.phonetic}", 14f, faceMuted, false, Gravity.CENTER))
            }
            if (cardWord.partOfSpeech.isNotBlank()) {
                content.addView(vSpace(7))
                content.addView(text(cardWord.partOfSpeech, 13f, accent, true, Gravity.CENTER))
            }
            val frequency = frequencyText(cardWord)
            if (frequency.isNotBlank()) {
                content.addView(vSpace(8))
                content.addView(text(frequency, 11f, faceMuted, false, Gravity.CENTER))
            }
            content.addView(vSpace(15))
            content.addView(
                text(
                    if (active) "点击翻面" else backLabel,
                    11f, faceMuted, false, Gravity.CENTER
                )
            )
            face.addView(content, LinearLayout.LayoutParams(-1, -1))
            return face
        }

        fun createUnderCard(cardWord: VocabularyWord, label: String): android.widget.FrameLayout {
            val layer = android.widget.FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                elevation = 0f
                visibility = View.GONE
                alpha = 0.82f
            }
            layer.addView(
                createCardFace(cardWord, englishIsFront, false, label),
                android.widget.FrameLayout.LayoutParams(-1, -1)
            )
            return layer
        }

        val stage = android.widget.FrameLayout(this).apply {
            tag = "browse_card_stage"
            clipChildren = false
            clipToPadding = false
            cameraDistance = dp(32000).toFloat()
        }
        parent.clipChildren = false
        parent.clipToPadding = false

        // Keep both directions ready under the top card. The gesture decides which
        // layer is revealed, so left and right swipes never reuse the wrong card.
        val previousLayer = browseQueue.getOrNull(browseIndex - 1)
            ?.let { db.getWord(it) }
            ?.let { createUnderCard(it, "点击翻面") }
        val nextLayer = browseQueue.getOrNull(browseIndex + 1)
            ?.let { db.getWord(it) }
            ?.let { createUnderCard(it, "点击翻面") }

        previousLayer?.let {
            stage.addView(it, android.widget.FrameLayout.LayoutParams(-1, dp(cardHeightDp)))
        }
        nextLayer?.let {
            stage.addView(it, android.widget.FrameLayout.LayoutParams(-1, dp(cardHeightDp)))
        }

        val front = createCardFace(word, englishIsFront, true, "")
        val back = createCardFace(word, !englishIsFront, true, "").apply {
            rotationY = 180f
        }
        val topBody = android.widget.FrameLayout(this).apply {
            cameraDistance = dp(32000).toFloat()
            clipChildren = false
            clipToPadding = false
            elevation = 0f
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        front.visibility = if (browseCardFlipped) View.INVISIBLE else View.VISIBLE
        back.visibility = if (browseCardFlipped) View.VISIBLE else View.INVISIBLE
        topBody.rotationY = if (browseCardFlipped) 180f else 0f
        topBody.addView(front, android.widget.FrameLayout.LayoutParams(-1, -1))
        topBody.addView(back, android.widget.FrameLayout.LayoutParams(-1, -1))
        stage.addView(topBody, android.widget.FrameLayout.LayoutParams(-1, dp(cardHeightDp)))
        parent.addView(stage, LinearLayout.LayoutParams(-1, dp(cardHeightDp)))

        fun incomingLayer(direction: Int): android.widget.FrameLayout? =
            if (direction < 0) nextLayer else previousLayer

        fun resetIncomingLayers() {
            listOfNotNull(previousLayer, nextLayer).forEach { layer ->
                layer.animate().cancel()
                layer.visibility = View.GONE
                layer.translationX = 0f
                layer.translationY = 0f
                layer.alpha = 0.82f
            }
        }

        fun restoreStack() {
            if (browseMotionState != BrowseMotionState.DRAGGING) return
            browseMotionState = BrowseMotionState.SETTLING
            topBody.animate().cancel()

            val incoming = listOfNotNull(previousLayer, nextLayer)
                .firstOrNull { it.visibility == View.VISIBLE }
            val animations = mutableListOf<android.animation.Animator>(
                android.animation.ObjectAnimator.ofFloat(topBody, View.TRANSLATION_X, 0f),
                android.animation.ObjectAnimator.ofFloat(topBody, View.TRANSLATION_Y, 0f),
                android.animation.ObjectAnimator.ofFloat(topBody, View.ROTATION, 0f),
                android.animation.ObjectAnimator.ofFloat(topBody, View.ALPHA, 1f)
            )
            if (incoming != null) {
                val incomingDirection = if (incoming === nextLayer) -1 else 1
                animations += android.animation.ObjectAnimator.ofFloat(
                    incoming, View.TRANSLATION_X, dp(18 * -incomingDirection).toFloat()
                )
                animations += android.animation.ObjectAnimator.ofFloat(incoming, View.TRANSLATION_Y, dp(5).toFloat())
                animations += android.animation.ObjectAnimator.ofFloat(incoming, View.ALPHA, 0.82f)
            }

            var settled = false
            android.animation.AnimatorSet().apply {
                playTogether(animations)
                duration = 210L
                interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private fun finishOnce() {
                        if (settled) return
                        settled = true
                        resetIncomingLayers()
                        browseMotionState = BrowseMotionState.IDLE
                    }

                    override fun onAnimationEnd(animation: android.animation.Animator) = finishOnce()
                    override fun onAnimationCancel(animation: android.animation.Animator) = finishOnce()
                })
                start()
            }
        }

        fun showIncomingDuringDrag(direction: Int, progress: Float) {
            val selected = incomingLayer(direction)
            val other = if (direction < 0) previousLayer else nextLayer
            other?.visibility = View.GONE
            if (selected != null) {
                selected.visibility = View.VISIBLE
                selected.translationX = dp(18 * -direction).toFloat() * (1f - progress)
                selected.translationY = dp(5).toFloat() * (1f - progress)
                selected.alpha = (0.82f + 0.18f * progress).coerceIn(0.82f, 1f)
            }
        }

        fun animateCardChange(direction: Int) {
            if (browseMotionState != BrowseMotionState.DRAGGING) return

            // Swipe left advances the queue; swipe right reveals the previous item.
            // At the first item, settle the same drag back to rest without changing data.
            if (direction > 0 && browseIndex <= 0) {
                restoreStack()
                Toast.makeText(this, "已经是本轮第一张", Toast.LENGTH_SHORT).show()
                return
            }

            browseMotionState = BrowseMotionState.TRANSITIONING
            provideFeedback(isSwipe = true)

            val selected = incomingLayer(direction)
            val other = if (direction < 0) previousLayer else nextLayer
            other?.visibility = View.GONE
            // Keep the exact position and opacity reached by the finger. Resetting this
            // layer here made it visibly jump backwards just before its entrance motion.
            selected?.visibility = View.VISIBLE

            topBody.animate().cancel()
            val exitDistance = stage.width.coerceAtLeast(dp(280)).toFloat() * 1.12f
            val animations = mutableListOf<android.animation.Animator>(
                android.animation.ObjectAnimator.ofFloat(topBody, View.TRANSLATION_X, direction * exitDistance),
                android.animation.ObjectAnimator.ofFloat(topBody, View.TRANSLATION_Y, -dp(9).toFloat()),
                android.animation.ObjectAnimator.ofFloat(topBody, View.ROTATION, direction * 9f),
                android.animation.ObjectAnimator.ofFloat(topBody, View.ALPHA, 0f)
            )
            if (selected != null) {
                animations += android.animation.ObjectAnimator.ofFloat(selected, View.TRANSLATION_X, 0f)
                animations += android.animation.ObjectAnimator.ofFloat(selected, View.TRANSLATION_Y, 0f)
                animations += android.animation.ObjectAnimator.ofFloat(selected, View.ALPHA, 1f)
            }

            var committed = false
            fun finishTransition() {
                if (committed) return
                committed = true
                // Persist the new word only after the outgoing card has completed its exit.
                if (direction < 0) advanceBrowseWord() else showPreviousBrowseWord()
                browseMotionState = BrowseMotionState.IDLE
                resetIncomingLayers()
                if (selectedTab == TAB_BROWSE && !showingFavorites) renderPage()
            }

            android.animation.AnimatorSet().apply {
                playTogether(animations)
                duration = 285L
                interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = finishTransition()
                    override fun onAnimationCancel(animation: android.animation.Animator) = finishTransition()
                })
                start()
            }
        }

        var downX = 0f
        var downY = 0f
        var horizontalDrag = false
        var verticalDrag = false
        var moved = false
        var velocityTracker: android.view.VelocityTracker? = null
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        stage.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (browseMotionState != BrowseMotionState.IDLE) return@setOnTouchListener true
                    velocityTracker?.recycle()
                    velocityTracker = android.view.VelocityTracker.obtain().apply { addMovement(event) }
                    downX = event.rawX
                    downY = event.rawY
                    horizontalDrag = false
                    verticalDrag = false
                    moved = false
                    resetIncomingLayers()
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (browseMotionState != BrowseMotionState.IDLE &&
                        browseMotionState != BrowseMotionState.DRAGGING
                    ) return@setOnTouchListener true
                    velocityTracker?.addMovement(event)
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!horizontalDrag && !verticalDrag &&
                        (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)
                    ) {
                        if (kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.15f) {
                            horizontalDrag = true
                            browseMotionState = BrowseMotionState.DRAGGING
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        } else {
                            verticalDrag = true
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                            return@setOnTouchListener false
                        }
                    }
                    if (horizontalDrag) {
                        moved = true
                        val direction = if (dx < 0f) -1 else 1
                        val progress = (kotlin.math.abs(dx) / dp(220).toFloat()).coerceIn(0f, 1f)
                        showIncomingDuringDrag(direction, progress)
                        // One-to-one movement keeps the card attached to the finger.
                        topBody.translationX = dx
                        topBody.translationY = -dp(7).toFloat() * progress
                        topBody.rotation = (dx / stage.width.coerceAtLeast(1).toFloat() * 9f).coerceIn(-9f, 9f)
                        topBody.alpha = 1f
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (browseMotionState != BrowseMotionState.IDLE &&
                        browseMotionState != BrowseMotionState.DRAGGING
                    ) return@setOnTouchListener true

                    velocityTracker?.addMovement(event)
                    val velocityX = velocityTracker?.let {
                        it.computeCurrentVelocity(1000)
                        it.xVelocity
                    } ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null

                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val distanceThreshold = maxOf(dp(68).toFloat(), stage.width * 0.22f)
                    val flingDistance = maxOf(slop * 2f, stage.width * 0.08f)
                    val isQuickFling =
                        kotlin.math.abs(dx) >= flingDistance && kotlin.math.abs(velocityX) >= dp(850).toFloat()

                    if (horizontalDrag &&
                        (kotlin.math.abs(dx) >= distanceThreshold || isQuickFling) &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.15f
                    ) {
                        animateCardChange(if (dx < 0f) -1 else 1)
                    } else if (!verticalDrag && !moved &&
                        kotlin.math.abs(dx) < slop && kotlin.math.abs(dy) < slop
                    ) {
                        resetIncomingLayers()
                        browseMotionState = BrowseMotionState.FLIPPING
                        val from = if (browseCardFlipped) 180f else 0f
                        val to = if (browseCardFlipped) 0f else 180f
                        browseCardFlipped = !browseCardFlipped
                        android.animation.ValueAnimator.ofFloat(from, to).apply {
                            // A slightly shorter, ease-out turn feels immediate without
                            // making the card appear to pause at its edge.
                            duration = 430L
                            interpolator = android.view.animation.PathInterpolator(0.22f, 0.72f, 0.18f, 1f)
                            addUpdateListener { animator ->
                                val angle = animator.animatedValue as Float
                                topBody.rotationY = angle
                                val showFront = angle < 90f
                                front.visibility = if (showFront) View.VISIBLE else View.INVISIBLE
                                back.visibility = if (showFront) View.INVISIBLE else View.VISIBLE
                            }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    browseMotionState = BrowseMotionState.IDLE
                                }
                                override fun onAnimationCancel(animation: android.animation.Animator) {
                                    browseMotionState = BrowseMotionState.IDLE
                                }
                            })
                            start()
                        }
                    } else if (horizontalDrag) {
                        restoreStack()
                    } else {
                        resetIncomingLayers()
                    }
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    if (browseMotionState == BrowseMotionState.DRAGGING) restoreStack()
                    else if (browseMotionState == BrowseMotionState.IDLE) resetIncomingLayers()
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> true
            }
        }

        parent.addView(vSpace(12))
        val actions = row()
        actions.addView(pillButton("单词发音", false) { speak(word.word) },
            LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(hSpace(6))
        actions.addView(pillButton("朗读例句", false) {
            if (word.example.isNotBlank()) speak(word.example)
            else Toast.makeText(this, "这条词目暂时没有英文例句", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(hSpace(6))
        actions.addView(pillButton(if (word.favorite) "★ 已收藏" else "☆ 收藏", false) {
            db.setFavorite(word.id, !word.favorite)
            renderPage()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(actions)
        parent.addView(vSpace(7))
        parent.addView(text(
            "${book.name}  ·  已浏览 ${browseCount} 词",
            9f, secondary, false, Gravity.CENTER
        ))

        if (prefs.getBoolean(KEY_AUTO_SPEAK, false) && lastAutoSpokenWordId != word.id) {
            lastAutoSpokenWordId = word.id
            speak(word.word)
        }
    }

        private fun metricCard(value: String, caption: String): LinearLayout = card(14).apply {
        addView(text(value, 22f, ink, true))
        addView(vSpace(6))
        addView(text(caption, 11f, secondary))
    }

    /**
     * Formats meanings by real POS markers and source POS metadata.
     * Semicolons split major senses; enumeration commas stay compact.
     */
    private fun formatMeaning(raw: String, fallbackPos: String = ""): CharSequence {
        val source = raw.replace("\r\n", "\n").replace('　', ' ').trim()
        if (source.isBlank()) return source

        val posPattern = Regex("""(?i)(?<![A-Za-z])((?:prep&adv|prep&pron|determiner|infinitive|infm|vt|vi|adj|adv|prep|conj|pron|num|art|aux|int|abbr|pl|verb|article|det|neg|not|n|v))\.(?=\s|[/&,]|$)\s*""")

        fun canonicalPos(value: String): String = when (value.trim().lowercase().removeSuffix(".")) {
            "n", "noun", "名词" -> "n."
            "v", "vt", "vi", "verb", "动词" -> "v."
            "adj", "adjective", "形容词" -> "adj."
            "adv", "adverb", "副词" -> "adv."
            "prep", "preposition", "介词" -> "prep."
            "conj", "conjunction", "连词" -> "conj."
            "pron", "pronoun", "代词" -> "pron."
            "num", "numeral", "数词" -> "num."
            "art", "article", "冠词" -> "art."
            "aux", "auxiliary", "助动词" -> "aux."
            "int", "interjection", "感叹词" -> "int."
            "abbr", "abbreviation", "缩写" -> "abbr."
            "pl", "plural", "复数" -> "pl."
            "prep&adv" -> "prep.&adv."
            "prep&pron" -> "prep.&pron."
            "det", "determiner" -> "det."
            "infm", "infinitive" -> "infm."
            "neg", "not" -> "neg."
            else -> value.trim().let { if (it.endsWith(".")) it else "$it." }
        }

        // Split metadata such as "v./n." into separate, visibly styled POS tokens.
        val declaredPos = posPattern.findAll(fallbackPos)
            .map { canonicalPos(it.groupValues[1]) }
            .distinct()
            .toList()
            .ifEmpty {
                fallbackPos.split(Regex("""[/、,;；\s]+"""))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { canonicalPos(it) }
                    .distinct()
            }

        val markers = posPattern.findAll(source).toList()
        val groupedBodies = linkedMapOf<String, MutableList<String>>()
        fun addBody(pos: String, body: String) {
            if (body.isBlank()) return
            groupedBodies.getOrPut(pos.trim()) { mutableListOf() }.add(body.trim())
        }

        if (markers.isNotEmpty()) {
            val prefix = source.substring(0, markers.first().range.first).trim()
            if (prefix.isNotBlank()) addBody(declaredPos.firstOrNull().orEmpty(), prefix)
            markers.forEachIndexed { index, marker ->
                val bodyStart = marker.range.last + 1
                val bodyEnd = markers.getOrNull(index + 1)?.range?.first ?: source.length
                addBody(canonicalPos(marker.groupValues[1]), source.substring(bodyStart, bodyEnd))
            }
        } else {
            // A malformed or legacy entry may have no inline n./v. at all.
            // Show every available metadata POS separately rather than "v./n." as one label.
            addBody(declaredPos.joinToString(" "), source)
        }

        val result = android.text.SpannableStringBuilder()
        val bracketAnnotation = Regex("""\[([^\]]+)]\s*""")
        val chineseWordGap = Regex("""(?<=\p{IsHan})\s+(?=\p{IsHan})""")

        groupedBodies.forEach posGroupLoop@ { (posLabel, bodies) ->
            val senseGroups = mutableListOf<LinkedHashSet<String>>()
            val segments = bodies.flatMap { it.split(Regex("""[；;\n]+""")) }

            segments.forEach segmentLoop@ { rawSense ->
                var sense = rawSense.trim()
                if (sense.isBlank()) return@segmentLoop

                // "[病毒] 病毒" -> "病毒"; preserve bracket notes when they add information.
                val annotation = bracketAnnotation.find(sense)
                if (annotation != null) {
                    val note = annotation.groupValues[1].trim()
                    val remainder = sense.removeRange(annotation.range).trimStart()
                    if (note.isNotBlank() && remainder.startsWith(note)) sense = remainder
                }

                sense = chineseWordGap.replace(sense, "、")
                val glosses = sense.split(Regex("""[，,、]"""))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase().replace(Regex("""\s+"""), "") }
                if (glosses.isEmpty()) return@segmentLoop

                // Remove duplicate source entries while retaining genuinely different senses.
                val duplicateIndex = senseGroups.indexOfFirst { existing ->
                    glosses.all { candidate -> existing.any { it.equals(candidate, ignoreCase = true) } } ||
                    existing.all { old -> glosses.any { it.equals(old, ignoreCase = true) } }
                }
                if (duplicateIndex >= 0) {
                    senseGroups[duplicateIndex].addAll(glosses)
                } else {
                    senseGroups.add(LinkedHashSet(glosses))
                }
            }

            if (senseGroups.isEmpty()) return@posGroupLoop
            if (result.isNotEmpty()) result.append("\u2003")

            val posTokens = posLabel.split(Regex("""\s+""")).filter { it.isNotBlank() }
            posTokens.forEachIndexed { index, token ->
                if (index > 0) result.append(" ")
                val start = result.length
                result.append(token)
                val end = result.length
                result.setSpan(StyleSpan(Typeface.BOLD), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                result.setSpan(ForegroundColorSpan(accent), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (posTokens.isNotEmpty()) result.append(" ")

            senseGroups.forEachIndexed { senseIndex, glossGroup ->
                // Major-sense boundary is wider than the reduced-size enumeration comma.
                if (senseIndex > 0) result.append("\u2003")
                val senseStart = result.length
                glossGroup.forEachIndexed { glossIndex, gloss ->
                    if (glossIndex > 0) {
                        val punctuationStart = result.length
                        result.append("、")
                        result.setSpan(
                            RelativeSizeSpan(0.48f),
                            punctuationStart,
                            result.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    result.append(gloss)
                }
                if (senseIndex == 0) {
                    result.setSpan(StyleSpan(Typeface.BOLD), senseStart, result.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }

        return if (result.isNotEmpty()) result else source
    }

    private fun renderStudy(parent: LinearLayout) {
        // The daily plan is intentionally the first row; this quiz page has no back-navigation row.
        if (queue.isEmpty()) ensureQueue(forceNew = true)
        val book = activeBook()
        if (queue.isEmpty() || queueIndex >= queue.size) {
            renderSessionFinished(parent, book)
            return
        }

        val goal = queue.size
        val progress = (queueIndex.toFloat() / goal).coerceIn(0f, 1f)
        val progressHeader = row()
        progressHeader.addView(text("今日背词计划", 13f, secondary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        progressHeader.addView(text("${queueIndex + 1} / $goal", 13f, ink, true))
        parent.addView(progressHeader)
        parent.addView(vSpace(9))
        val progressTrack = LinearLayout(this).apply {
            background = rounded(line, 0, 6)
            clipToOutline = true
        }
        val progressFill = View(this).apply { background = rounded(accent, 0, 6) }
        progressTrack.addView(progressFill, LinearLayout.LayoutParams(0, dp(6)))
        parent.addView(progressTrack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
        progressTrack.post {
            val params = progressFill.layoutParams as LinearLayout.LayoutParams
            params.width = (progressTrack.width * progress).toInt()
            progressFill.layoutParams = params
        }
        parent.addView(vSpace(15))

        val word = db.getWord(queue[queueIndex])
        if (word == null) {
            nextQuizQuestion()
            renderPage()
            return
        }
        restoreQuizState(word.id)

        if (quizShowExplanation && quizSelectedOptionId != null) {
            renderQuizAnswer(parent, word)
            return
        }

        val direction = prefs.getString(KEY_QUIZ_DIRECTION, "mixed") ?: "mixed"
        val englishPrompt = when (direction) {
            "en_to_zh" -> true
            "zh_to_en" -> false
            else -> queueIndex % 2 == 0
        }
        val answered = quizSelectedOptionId != null
        val options = quizOptionsFor(word, englishPrompt)

        val questionCard = card(22).apply {
            minimumHeight = dp(485)
            background = rounded(white, line, 24)
        }
        val head = row()
        head.addView(text("第 ${queueIndex + 1} 题  ·  四选一", 11f, secondary, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text(if (englishPrompt) "EN → 中文" else "中文 → EN", 11f, accent, true))
        questionCard.addView(head)
        questionCard.addView(vSpace(25))
        questionCard.addView(text(if (englishPrompt) word.word else formatMeaning(word.meaning, word.partOfSpeech), if (englishPrompt) 34f else 21f, ink, true, Gravity.CENTER))
        if (englishPrompt) {
            if (word.phonetic.isNotBlank()) {
                questionCard.addView(vSpace(9))
                questionCard.addView(text(word.phonetic, 14f, secondary, false, Gravity.CENTER))
            }
            questionCard.addView(vSpace(7))
            questionCard.addView(quietButton("播放发音", { speak(word.word) }))
        }
        questionCard.addView(vSpace(22))
        questionCard.addView(text(if (englishPrompt) "请选择正确的中文释义" else "请选择对应的英文单词", 12f, secondary, false, Gravity.CENTER))
        questionCard.addView(vSpace(14))

        options.forEachIndexed { index, option ->
            val label = if (englishPrompt) formatMeaning(option.meaning, option.partOfSpeech) else option.word
            val isCorrectOption = option.id == word.id
            val isSelected = option.id == quizSelectedOptionId
            val fill = when {
                !answered -> white
                isCorrectOption -> softAccent
                isSelected -> 0xFFFFF1F1.toInt()
                else -> 0xFFF6F7F9.toInt()
            }
            val stroke = when {
                !answered -> line
                isCorrectOption -> accent
                isSelected -> 0xFFE0A3A8.toInt()
                else -> line
            }
            val color = when {
                !answered -> ink
                isCorrectOption -> accent
                isSelected -> 0xFFB04450.toInt()
                else -> secondary
            }
            val marker = when {
                answered && isCorrectOption -> "✓  "
                answered && isSelected -> "×  "
                else -> ""
            }
            questionCard.addView(
                quizOptionButton(android.text.SpannableStringBuilder().append("${index + 1}.  $marker").append(label), fill, stroke, color) {
                    if (!answered) {
                        chooseQuizOption(word, option)
                    } else {
                        quizShowExplanation = true
                        prefs.edit().putBoolean(KEY_QUIZ_SHOW_EXPLANATION, true).apply()
                        provideFeedback(isSwipe = true)
                        renderPage()
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            questionCard.addView(vSpace(9))
        }

        if (answered) {
            questionCard.addView(vSpace(7))
            val correct = quizAnswerCorrect == true
            val feedbackCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(if (correct) softAccent else 0xFFFFF3F3.toInt(), 0, 14)
            }
            feedbackCard.addView(text(if (correct) "回答正确  ✓" else "回答错误  ×", 16f, if (correct) accent else 0xFFB04450.toInt(), true))
            feedbackCard.addView(vSpace(4))
            val correctAnswer: CharSequence = if (englishPrompt) {
                android.text.SpannableStringBuilder().append("正确答案：").append(formatMeaning(word.meaning, word.partOfSpeech))
            } else {
                "正确答案：${word.word}"
            }
            feedbackCard.addView(text(correctAnswer, 12f, ink))
            feedbackCard.addView(vSpace(5))
            feedbackCard.addView(text("点击任意选项进入本题解析。", 11f, secondary))
            questionCard.addView(feedbackCard)
            questionCard.addView(vSpace(9))
            questionCard.addView(quietButton("查看详细解析  →", {
                quizShowExplanation = true
                prefs.edit().putBoolean(KEY_QUIZ_SHOW_EXPLANATION, true).apply()
                renderPage()
            }))
        }

        parent.addView(questionCard)
        parent.addView(vSpace(14))
        parent.addView(row().apply {
            addView(text("答对后巩固记忆，答错后进入重点复习。", 11f, secondary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(quietButton("跳过", { nextQuizQuestion(); renderPage() }))
        })
        parent.addView(vSpace(10))
        parent.addView(text("${book.name}  ·  每日目标 ${prefs.getInt(KEY_GOAL, 30)} 词", 10f, secondary, false, Gravity.CENTER))
    }

    private fun quizOptionButton(
        label: CharSequence,
        fillColor: Int,
        strokeColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): TextView = pillButton(label, false, onClick).apply {
        setTextColor(textColor)
        textSize = 16.5f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(17), dp(15), dp(15), dp(15))
        minimumHeight = dp(54)
        background = rounded(fillColor, strokeColor, 15)
    }

    private fun quizOptionsFor(word: VocabularyWord, englishPrompt: Boolean): List<VocabularyWord> {
        val targetKey = if (englishPrompt) word.meaning.trim().lowercase() else word.word.trim().lowercase()
        val used = mutableSetOf(targetKey)
        val all = db.getWords(currentBookId)
        val seed = word.id xor (queueIndex.toLong() shl 32) xor (if (englishPrompt) 0x51A7L else 0x27B3L)
        val distractors = all.asSequence()
            .filter { it.id != word.id }
            .filter { candidate ->
                val key = if (englishPrompt) candidate.meaning.trim().lowercase() else candidate.word.trim().lowercase()
                key.isNotBlank() && used.add(key)
            }
            .shuffled(Random(seed))
            .take(3)
            .toList()
        return (distractors + word).shuffled(Random(seed xor 0x6D2BL))
    }

    private fun restoreQuizState(wordId: Long) {
        if (quizCurrentWordId == wordId) return
        quizCurrentWordId = wordId
        val savedWordId = prefs.getLong(KEY_QUIZ_WORD_ID, -1L)
        if (savedWordId == wordId) {
            val selected = prefs.getLong(KEY_QUIZ_SELECTED_ID, -1L)
            quizSelectedOptionId = selected.takeIf { it >= 0L }
            quizAnswerCorrect = if (quizSelectedOptionId != null) prefs.getBoolean(KEY_QUIZ_CORRECT, false) else null
            quizShowExplanation = quizSelectedOptionId != null && prefs.getBoolean(KEY_QUIZ_SHOW_EXPLANATION, false)
        } else {
            quizSelectedOptionId = null
            quizAnswerCorrect = null
            quizShowExplanation = false
        }
    }

    private fun chooseQuizOption(word: VocabularyWord, option: VocabularyWord) {
        if (quizCurrentWordId != word.id || quizSelectedOptionId != null) return
        val correct = option.id == word.id
        quizSelectedOptionId = option.id
        quizAnswerCorrect = correct
        quizShowExplanation = false
        db.saveReview(word.id, if (correct) 2 else 0)
        prefs.edit()
            .putLong(KEY_QUIZ_WORD_ID, word.id)
            .putLong(KEY_QUIZ_SELECTED_ID, option.id)
            .putBoolean(KEY_QUIZ_CORRECT, correct)
            .putBoolean(KEY_QUIZ_SHOW_EXPLANATION, false)
            .apply()
        provideFeedback(isCorrect = correct)
        renderPage()
    }

    private fun nextQuizQuestion() {
        queueIndex = (queueIndex + 1).coerceAtMost(queue.size)
        quizCurrentWordId = -1L
        quizSelectedOptionId = null
        quizAnswerCorrect = null
        quizShowExplanation = false
        prefs.edit()
            .remove(KEY_QUIZ_WORD_ID)
            .remove(KEY_QUIZ_SELECTED_ID)
            .remove(KEY_QUIZ_CORRECT)
            .remove(KEY_QUIZ_SHOW_EXPLANATION)
            .apply()
        saveQueue()
    }

    private fun renderQuizAnswer(parent: LinearLayout, word: VocabularyWord) {
        val correct = quizAnswerCorrect == true

        val resultCard = card(18)
        resultCard.background = rounded(if (correct) softAccent else 0xFFFFF3F3.toInt(), 0, 18)
        resultCard.addView(text(if (correct) "回答正确" else "这道题需要再巩固", 22f, if (correct) accent else 0xFFB04450.toInt(), true))
        resultCard.addView(vSpace(6))
        resultCard.addView(text(if (correct) "答对了，继续保持。" else "本题已记录为需要复习。正确答案如下。", 12f, secondary))
        parent.addView(resultCard)
        parent.addView(vSpace(15))

        val wordCard = card(20)
        wordCard.addView(row().apply {
            addView(text(word.word, 31f, ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(if (word.favorite) "★" else "☆", 23f, if (word.favorite) accent else secondary, false, Gravity.CENTER).apply {
                setOnClickListener {
                    db.setFavorite(word.id, !word.favorite)
                    renderPage()
                }
            })
        })
        if (word.phonetic.isNotBlank()) {
            wordCard.addView(vSpace(7))
            wordCard.addView(text(word.phonetic, 14f, secondary))
        }
        if (word.partOfSpeech.isNotBlank()) {
            wordCard.addView(vSpace(6))
            wordCard.addView(text(word.partOfSpeech, 12f, accent, true))
        }
        wordCard.addView(vSpace(12))
        wordCard.addView(text("核心释义", 11f, accent, true))
        wordCard.addView(vSpace(6))
        wordCard.addView(text(formatMeaning(word.meaning, word.partOfSpeech), 19f, ink))
        wordCard.addView(vSpace(11))
        wordCard.addView(row().apply {
            addView(pillButton("播放单词", false) { speak(word.word) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(hSpace(8))
            addView(pillButton(if (word.favorite) "已收藏" else "收藏", false) {
                db.setFavorite(word.id, !word.favorite)
                renderPage()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        parent.addView(wordCard)
        parent.addView(vSpace(12))

        if (word.example.isNotBlank()) {
            renderExampleCard(parent, word, word.example, word.exampleZh, "课本例句")
        }

        renderOnlineDictionarySection(parent, word)

        parent.addView(vSpace(16))
        parent.addView(pillButton("下一题  →", true) {
            nextQuizQuestion()
            renderPage()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun renderExampleCard(
        parent: LinearLayout,
        word: VocabularyWord,
        sentence: String,
        knownTranslation: String,
        title: String,
        textSizeDelta: Float = 0f
    ) {
        val card = card(17)
        card.addView(row().apply {
            addView(text(title, 12f + textSizeDelta, accent, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(quietButton("朗读  ▶", { speak(sentence) }))
        })
        card.addView(vSpace(9))
        val english = text(sentence, 15f + textSizeDelta, ink)
        english.text = highlightWord(sentence, word.word)
        card.addView(english)
        card.addView(vSpace(9))

        val cached = knownTranslation.takeIf { it.isNotBlank() }
            ?: translatedExamples[sentence]
            ?: db.getExampleTranslation(sentence).also { if (!it.isNullOrBlank()) translatedExamples[sentence] = it }
        if (!cached.isNullOrBlank()) {
            card.addView(text(if (knownTranslation.isNotBlank()) knownTranslation else "参考译文：$cached", 13f + textSizeDelta, if (knownTranslation.isNotBlank()) secondary else accent))
        } else {
            val status = when {
                translationLoading.contains(sentence) -> "正在获取例句翻译…"
                translationFailed.contains(sentence) -> "暂未获取到译文，可稍后重试。"
                !prefs.getBoolean(KEY_ONLINE_DETAIL, true) -> "本词条暂时没有收录中文例句。"
                else -> "正在准备参考译文…"
            }
            val translationStatus = text(status, 11f + textSizeDelta, secondary)
            card.addView(translationStatus)
            if (prefs.getBoolean(KEY_ONLINE_DETAIL, true) && !translationLoading.contains(sentence) && !translationFailed.contains(sentence)) {
                translationLoading.add(sentence)
                Thread {
                    val translated = ExampleTranslator.translate(sentence)
                    runOnUiThread {
                        translationLoading.remove(sentence)
                        if (!translated.isNullOrBlank()) {
                            translatedExamples[sentence] = translated
                            db.putExampleTranslation(sentence, translated)
                            translationStatus.text = if (knownTranslation.isNotBlank()) knownTranslation else "参考译文：$translated"
                            translationStatus.setTextColor(if (knownTranslation.isNotBlank()) secondary else accent)
                        } else {
                            translationFailed.add(sentence)
                            translationStatus.text = "暂未获取到译文，可稍后重试。"
                        }
                    }
                }.start()
            }
        }
        parent.addView(card)
        parent.addView(vSpace(10))
    }

    private fun highlightWord(sentence: String, target: String): CharSequence {
        val span = SpannableString(sentence)
        if (target.isBlank()) return span
        var start = sentence.indexOf(target, ignoreCase = true)
        while (start >= 0) {
            span.setSpan(ForegroundColorSpan(accent), start, start + target.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(StyleSpan(Typeface.BOLD), start, start + target.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = sentence.indexOf(target, start + target.length, ignoreCase = true)
        }
        return span
    }

    private fun openYoudaoWord(word: String) {
        val cleaned = word.trim()
        if (cleaned.isEmpty()) return
        val url = "https://dict.youdao.com/w/${Uri.encode(cleaned)}"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "暂时无法打开有道词典网页", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chinesePartOfSpeech(value: String): String {
        val key = value.trim().lowercase()
        return when (key) {
            "noun" -> "名词"
            "verb" -> "动词"
            "adjective" -> "形容词"
            "adverb" -> "副词"
            "preposition" -> "介词"
            "conjunction" -> "连词"
            "pronoun" -> "代词"
            "interjection" -> "感叹词"
            "determiner" -> "限定词"
            "numeral" -> "数词"
            "proper noun" -> "专有名词"
            "auxiliary verb" -> "助动词"
            "article" -> "冠词"
            "prefix" -> "前缀"
            "suffix" -> "后缀"
            else -> value.ifBlank { "词义" }
        }
    }

    private fun requestDictionaryDefinitionTranslation(definition: String, statusView: TextView) {
        if (definition.isBlank()) return
        val cached = translatedExamples[definition] ?: db.getExampleTranslation(definition)
        if (!cached.isNullOrBlank()) {
            statusView.text = "中文释义\n$cached"
            statusView.setTextColor(ink)
            return
        }

        // A dictionary refresh may rebuild the section while a translation is
        // still running. Register every visible label so none is left spinning.
        definitionTranslationViews.getOrPut(definition) { mutableListOf() }.add(statusView)
        if (translationLoading.contains(definition) || translationFailed.contains(definition)) return

        translationLoading.add(definition)
        Thread {
            val translated = ExampleTranslator.translate(definition)
            runOnUiThread {
                translationLoading.remove(definition)
                if (!translated.isNullOrBlank()) {
                    translatedExamples[definition] = translated
                    db.putExampleTranslation(definition, translated)
                    translationFailed.remove(definition)
                } else {
                    translationFailed.add(definition)
                }
                // Update labels in place; never rebuild the whole answer page.
                val labels = definitionTranslationViews.remove(definition).orEmpty()
                labels.forEach { label ->
                    if (!translated.isNullOrBlank()) {
                        label.text = "中文释义\n$translated"
                        label.setTextColor(ink)
                    } else {
                        label.text = "中文释义暂时获取失败。"
                        label.setTextColor(secondary)
                    }
                }
            }
        }.start()
    }

    private fun extractExampleCollocations(word: String, detail: DictionaryDetails): List<String> {
        val variants = linkedSetOf(word.trim().lowercase())
        detail.allForms(limit = 30).forEach { form ->
            val formWord = form.substringAfter("：", "").trim().lowercase()
            if (formWord.isNotBlank()) variants.add(formWord)
        }
        val output = linkedSetOf<String>()
        val tokenPattern = Regex("[A-Za-z]+(?:['’][A-Za-z]+)?")
        for (sentence in detail.allExamples(limit = 24)) {
            val tokens = tokenPattern.findAll(sentence).toList()
            for (index in tokens.indices) {
                if (tokens[index].value.lowercase() !in variants) continue
                val windows = listOf(
                    (index - 2).coerceAtLeast(0) to (index + 2).coerceAtMost(tokens.lastIndex),
                    (index - 1).coerceAtLeast(0) to (index + 1).coerceAtMost(tokens.lastIndex),
                    index to (index + 2).coerceAtMost(tokens.lastIndex)
                )
                for ((start, end) in windows) {
                    if (end - start + 1 !in 3..5) continue
                    val phrase = tokens.subList(start, end + 1).joinToString(" ") { it.value }
                    if (phrase.split(" ").any { it.lowercase() in variants }) output.add(phrase)
                    if (output.size >= 8) break
                }
                if (output.size >= 8) break
            }
            if (output.size >= 8) break
        }
        return output.toList()
    }

    private fun renderOnlineDictionarySection(parent: LinearLayout, word: VocabularyWord, existingSection: LinearLayout? = null) {
        fun dictionaryText(value: CharSequence, size: Float = 14f, color: Int = ink, bold: Boolean = false, gravity: Int = Gravity.START): TextView =
            text(value, size + 1f, color, bold, gravity)

        val key = word.word.trim().lowercase()
        val cachedJson = db.getDictionaryCache(key)
        if (!onlineDetails.containsKey(key) && !cachedJson.isNullOrBlank()) {
            DictionaryLookup.parse(cachedJson)?.let { onlineDetails[key] = it }
        }
        val detail = onlineDetails[key]
            ?: cachedJson?.let { DictionaryLookup.parse(it) }?.also { onlineDetails[key] = it }

        val section = existingSection ?: card(17)
        if (existingSection != null) section.removeAllViews()
        val header = row()
        header.addView(
            dictionaryText("词典拓展", 16f, ink, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(quietButton("有道完整词条 ↗") { openYoudaoWord(word.word) })
        section.addView(header)
        section.addView(vSpace(4))
        section.addView(dictionaryText("中文核心释义、词组搭配、词形变化和实际用法。在线译文仅供参考。", 10f, secondary))
        section.addView(vSpace(10))

        if (!prefs.getBoolean(KEY_ONLINE_DETAIL, true)) {
            section.addView(dictionaryText("应用内联网拓展已关闭，本地释义仍可使用。", 12f, secondary))
            if (existingSection == null) parent.addView(section)
            return
        }

        var dictionaryStatusView: TextView? = null
        if (detail == null && !onlineLoading.contains(key)) {
            onlineLoading.add(key)
            Thread {
                val raw = DictionaryLookup.fetchRaw(word.word)
                runOnUiThread {
                    onlineLoading.remove(key)
                    if (!raw.isNullOrBlank()) {
                        db.putDictionaryCache(key, raw)
                        val parsed = DictionaryLookup.parse(raw)
                        if (parsed != null) {
                            onlineDetails[key] = parsed
                            onlineFailed.remove(key)
                            // Refresh this same answer page immediately. The learner
                            // never has to leave the question and open the explanation again.
                            if (selectedTab == TAB_STUDY && quizShowExplanation && quizCurrentWordId == word.id) {
                                renderOnlineDictionarySection(parent, word, section)
                            } else {
                                dictionaryStatusView?.text = "在线词典资料已更新。"
                            }
                        } else {
                            onlineFailed.add(key)
                            dictionaryStatusView?.text = "词典返回了资料，但暂时无法整理。可打开有道查看完整词条。"
                        }
                    } else {
                        onlineFailed.add(key)
                        dictionaryStatusView?.text = "暂时没有获取到应用内词典资料，可检查网络或打开有道词典。"
                    }
                }
            }.start()
        }

        if (detail == null) {
            dictionaryStatusView = dictionaryText(
                when {
                    onlineLoading.contains(key) -> "正在获取词典释义、例句与词形变化…"
                    onlineFailed.contains(key) -> "暂时没有获取到应用内词典资料。"
                    else -> "正在准备词典资料…"
                }, 11f, secondary
            )
            section.addView(dictionaryStatusView)
            if (onlineFailed.contains(key)) {
                section.addView(vSpace(8))
                section.addView(pillButton("重新查询", false) {
                    onlineFailed.remove(key)
                    renderPage()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            if (existingSection == null) parent.addView(section)
            return
        }

        val orderedSenses = detail.entries.flatMap { entry ->
            entry.senses.map { sense -> entry to sense }
        }.sortedWith(
            compareBy<Pair<DictionaryEntryDetail, DictionarySense>> {
                if (it.second.chineseTranslations.isNotEmpty()) 0 else 1
            }.thenBy {
                if (it.second.examples.isNotEmpty()) 0 else 1
            }
        ).take(4)

        if (orderedSenses.isNotEmpty()) {
            section.addView(dictionaryText("核心释义", 13f, accent, true))
            section.addView(vSpace(7))
            orderedSenses.forEachIndexed { index, pair ->
                val entry = pair.first
                val sense = pair.second
                val senseCard = column().apply {
                    setPadding(dp(13), dp(11), dp(13), dp(11))
                    background = rounded(0xFFF7F8FA.toInt(), 0, 13)
                }
                val senseHeader = row()
                senseHeader.addView(dictionaryText(chinesePartOfSpeech(entry.partOfSpeech), 11f, accent, true))
                senseHeader.addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                senseHeader.addView(dictionaryText("义项 ${index + 1}", 9f, secondary))
                senseCard.addView(senseHeader)
                senseCard.addView(vSpace(6))

                if (sense.chineseTranslations.isNotEmpty()) {
                    senseCard.addView(
                        dictionaryText(sense.chineseTranslations.take(3).joinToString("；"), 15f, ink, true)
                    )
                    senseCard.addView(vSpace(5))
                }

                val definition = sense.definition
                val cachedTranslation = translatedExamples[definition]
                    ?: db.getExampleTranslation(definition)?.also { translatedExamples[definition] = it }

                if (!cachedTranslation.isNullOrBlank()) {
                    senseCard.addView(dictionaryText("中文释义", 9f, accent, true))
                    senseCard.addView(vSpace(3))
                    senseCard.addView(dictionaryText(cachedTranslation, 14f, ink, true))
                } else {
                    val definitionBytes = definition.toByteArray(Charsets.UTF_8).size
                    when {
                        definitionBytes > 480 ->
                            senseCard.addView(dictionaryText("该条英文解释较长，暂不自动翻译。", 11f, secondary))
                        translationLoading.contains(definition) -> {
                            val translationStatus = dictionaryText("正在生成中文释义参考…", 12f, secondary)
                            senseCard.addView(translationStatus)
                            requestDictionaryDefinitionTranslation(definition, translationStatus)
                        }
                        translationFailed.contains(definition) -> {
                            senseCard.addView(dictionaryText("中文释义暂时获取失败。", 11f, secondary))
                            senseCard.addView(quietButton("重试中文释义", {
                                translationFailed.remove(definition)
                                renderPage()
                            }))
                        }
                        else -> {
                            val translationStatus = dictionaryText("正在生成中文释义参考…", 12f, secondary)
                            senseCard.addView(translationStatus)
                            requestDictionaryDefinitionTranslation(definition, translationStatus)
                        }
                    }
                }
                senseCard.addView(vSpace(6))
                senseCard.addView(dictionaryText("英文原释义：$definition", 10f, secondary))
                section.addView(senseCard)
                section.addView(vSpace(7))
            }
        } else {
            section.addView(dictionaryText("该词条暂时没有可用的详细释义。", 12f, secondary))
        }

        val explicitPhrases = detail.allCollocations(limit = 12)
        val examplePhrases = extractExampleCollocations(word.word, detail)
        val phrases = (explicitPhrases + examplePhrases).distinct().take(8)
        section.addView(vSpace(5))
        section.addView(dictionaryText("词组搭配", 13f, accent, true))
        section.addView(vSpace(4))
        section.addView(dictionaryText(
            if (explicitPhrases.isNotEmpty())
                "优先显示词典提供的搭配；其余为从例句截取的搭配线索。"
            else
                "当前词典未单独提供固定搭配，以下从真实例句中截取包含本词的搭配片段。",
            10f, secondary
        ))
        if (phrases.isEmpty()) {
            section.addView(vSpace(5))
            section.addView(dictionaryText("暂未收录可用搭配。", 11f, secondary))
        } else {
            phrases.forEach { phrase ->
                val phraseRow = row()
                phraseRow.addView(
                    dictionaryText(phrase, 12f, ink, true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                phraseRow.addView(quietButton("查释义 ↗") { openYoudaoWord(phrase) })
                section.addView(phraseRow)
                section.addView(vSpace(3))
            }
        }

        val forms = detail.allForms(limit = 16)
        if (forms.isNotEmpty()) {
            section.addView(vSpace(9))
            section.addView(dictionaryText("词形变化", 13f, accent, true))
            section.addView(vSpace(5))
            forms.forEach { form ->
                val label = form.substringBefore("：", "其他形式")
                val value = form.substringAfter("：", form)
                val formRow = row()
                formRow.addView(
                    dictionaryText(label, 11f, secondary, true),
                    LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT)
                )
                formRow.addView(dictionaryText(value, 12f, ink, true))
                section.addView(formRow)
                section.addView(vSpace(4))
            }
        }

        section.addView(vSpace(7))
        section.addView(dictionaryText("词根与同根词", 12f, accent, true))
        section.addView(vSpace(4))
        section.addView(dictionaryText(
            "当前开放词典接口没有可靠的词根字段，因此不会按拼写猜测词根。可通过有道完整词条查看同根词栏目。",
            10f, secondary
        ))
        section.addView(quietButton("查看同根词与词源 ↗") { openYoudaoWord(word.word) })

        if (detail.synonyms.isNotEmpty() || detail.antonyms.isNotEmpty()) {
            section.addView(vSpace(9))
            section.addView(dictionaryText("近义词与反义词", 12f, accent, true))
            if (detail.synonyms.isNotEmpty()) {
                section.addView(vSpace(4))
                section.addView(dictionaryText("近义：${detail.synonyms.take(8).joinToString("  ·  ")}", 11f, ink))
            }
            if (detail.antonyms.isNotEmpty()) {
                section.addView(vSpace(4))
                section.addView(dictionaryText("反义：${detail.antonyms.take(8).joinToString("  ·  ")}", 11f, ink))
            }
        }

        val primaryTranslations = orderedSenses.flatMap { it.second.chineseTranslations }.toSet()
        val extraTranslations = detail.allChineseTranslations()
            .filterNot { it in primaryTranslations }
            .distinct()
            .take(8)
        if (extraTranslations.isNotEmpty()) {
            section.addView(vSpace(9))
            section.addView(dictionaryText("补充译义", 11f, accent, true))
            section.addView(vSpace(4))
            section.addView(dictionaryText(extraTranslations.joinToString("；"), 11f, secondary))
        }

        val dictionaryExamples = detail.allExamples(limit = 2).filter { it != word.example }
        if (dictionaryExamples.isNotEmpty()) {
            section.addView(vSpace(9))
            section.addView(dictionaryText("典型用法与例句", 12f, accent, true))
            section.addView(vSpace(5))
            dictionaryExamples.forEachIndexed { index, example ->
                renderExampleCard(section, word, example, "", "例句 ${index + 1}", textSizeDelta = 1f)
            }
        }

        section.addView(vSpace(10))
        section.addView(dictionaryText(
            "应用内资料来源：FreeDictionaryAPI.com / Wiktionary · ${detail.licenseName}",
            9f, secondary
        ))
        section.addView(vSpace(3))
        section.addView(quietButton("查看 Wiktionary 原始词条与授权说明 ↗", {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(detail.sourceUrl))) }
        }))
        if (existingSection == null) parent.addView(section)
    }

        private fun resetCurrentQuizForSettings() {
        if (quizSelectedOptionId == null) {
            quizCurrentWordId = -1L
            quizAnswerCorrect = null
            quizShowExplanation = false
        }
    }

    private fun animateCardIn(view: View, offsetY: Int = 12) {
        view.alpha = 0f
        view.translationY = dp(offsetY).toFloat()
        view.animate().alpha(1f).translationY(0f)
            .setDuration(230).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun provideFeedback(isCorrect: Boolean? = null, isSwipe: Boolean = false) {
        if (prefs.getBoolean(KEY_FEEDBACK_SOUND, false)) {
            val tone = when {
                isSwipe -> ToneGenerator.TONE_PROP_BEEP
                isCorrect == true -> ToneGenerator.TONE_PROP_ACK
                else -> ToneGenerator.TONE_PROP_NACK
            }
            if (toneGenerator == null) toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 42)
            toneGenerator?.startTone(tone, if (isSwipe) 45 else 90)
        }
        if (prefs.getBoolean(KEY_FEEDBACK_HAPTICS, true)) {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                val duration = when {
                    isSwipe -> 14L
                    isCorrect == true -> 24L
                    else -> 42L
                }
                val amplitude = if (isCorrect == false) 90 else 55
                runCatching { vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude)) }
            }
        }
    }

    private fun showPreviousBrowseWord() {
        if (browseIndex <= 0) {
            Toast.makeText(this, "已经是本轮第一个单词", Toast.LENGTH_SHORT).show()
            return
        }
        browseIndex -= 1
        lastAutoSpokenWordId = -1L
        browseCardFlipped = false
        saveBrowseState()
    }

    private fun swipeBrowse(direction: Int) {
        if (browseMotionState != BrowseMotionState.IDLE) return
        browseCardFlipped = false
        if (direction < 0) advanceBrowseWord() else showPreviousBrowseWord()
        provideFeedback(isSwipe = true)
        renderPage()
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


    private fun connectListeningPlayback() {
        val token = SessionToken(this, ComponentName(this, ListeningPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        listeningControllerFuture = future
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                listeningMediaController = future.get()
            } catch (_: Exception) {
                listeningMediaController = null
            }
        }, Executor { command -> Handler(Looper.getMainLooper()).post(command) })
    }

    private fun playListeningAudio(url: String, episode: ListeningEpisode) {
        listeningSegmentStopRunnable?.let { listeningPlaybackHandler.removeCallbacks(it) }
        listeningSegmentStopRunnable = null
        val controller = listeningMediaController
        if (controller == null) {
            Toast.makeText(this, "播放器正在连接，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }

        listeningPlaybackCoordinator.play(controller, episode, url)
    }

    private fun pauseListeningAudio() {
        listeningMediaController?.let {
            listeningPlaybackCoordinator.checkpoint(it)
            it.pause()
        }
    }

    private fun playListeningEpisode(episode: ListeningEpisode) {
        val audioUrl = episode.audioUrl
        if (!audioUrl.isNullOrBlank()) {
            tts.stop()
            playListeningAudio(audioUrl, episode)
            return
        }
        val sentences = listeningRepository.getSentencesForAttempt(listeningAttemptId)
        if (!ttsReady) {
            Toast.makeText(this, ttsStatusMessage, Toast.LENGTH_SHORT).show()
            return
        }
        pauseListeningAudio()
        tts.setSpeechRate(episode.speechRate.coerceIn(0.75f, 1.15f))
        tts.speak(
            sentences.joinToString(" ") { it.english },
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            "listening-${System.currentTimeMillis()}"
        )
    }

    private fun playListeningSentence(episode: ListeningEpisode, sentence: ListeningSentence) {
        val audioUrl = episode.audioUrl
        if (!audioUrl.isNullOrBlank() && sentence.endMs > sentence.startMs) {
            val controller = listeningMediaController
            if (controller == null) {
                Toast.makeText(this, "播放器正在连接，请稍后再试", Toast.LENGTH_SHORT).show()
                return
            }
            listeningSegmentStopRunnable?.let { listeningPlaybackHandler.removeCallbacks(it) }
            val sentenceMediaItem = MediaItem.Builder()
                .setMediaId(episode.id)
                .setUri(audioUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(episode.title)
                        .setArtist("Words · 听力训练")
                        .build()
                )
                .build()
            controller.setMediaItem(sentenceMediaItem, sentence.startMs)
            controller.prepare()
            controller.play()
            val segmentEnd = sentence.endMs
            val stopRunnable = object : Runnable {
                override fun run() {
                    val activeController = listeningMediaController ?: return
                    val activeUri = activeController.currentMediaItem?.localConfiguration?.uri?.toString()
                    if (activeUri != audioUrl) {
                        listeningSegmentStopRunnable = null
                        return
                    }
                    if (activeController.isPlaying && activeController.currentPosition >= segmentEnd) {
                        activeController.pause()
                        listeningSegmentStopRunnable = null
                    } else if (activeController.isPlaying) {
                        listeningPlaybackHandler.postDelayed(this, 80L)
                    } else {
                        listeningSegmentStopRunnable = null
                    }
                }
            }
            listeningSegmentStopRunnable = stopRunnable
            listeningPlaybackHandler.postDelayed(stopRunnable, 80L)
        } else if (ttsReady) {
            pauseListeningAudio()
            tts.speak(sentence.english, TextToSpeech.QUEUE_FLUSH, Bundle(), "listening-sentence-${sentence.id}-${System.currentTimeMillis()}")
        } else {
            Toast.makeText(this, ttsStatusMessage, Toast.LENGTH_SHORT).show()
        }
    }

    private fun persistListeningAttemptUiState() {
        if (listeningAttemptId <= 0L) return
        prefs.edit()
            .putLong(KEY_LISTENING_ATTEMPT_ID, listeningAttemptId)
            .putInt(KEY_LISTENING_QUESTION_INDEX, listeningQuestionIndex)
            .putLong(KEY_LISTENING_SELECTED_CHOICE_ID, listeningSelectedChoiceId ?: -1L)
            .apply()
    }

    private fun clearActiveListeningAttempt() {
        listeningArchiveAssignmentDate = null
        prefs.edit()
            .remove(KEY_LISTENING_ATTEMPT_ID)
            .remove(KEY_LISTENING_QUESTION_INDEX)
            .remove(KEY_LISTENING_SELECTED_CHOICE_ID)
            .apply()
        listeningAttemptId = -1L
        listeningQuestionIndex = 0
        listeningSelectedChoiceId = null
        listeningSubmitted = false
        listeningCurrentCorrect = null
        listeningCorrectChoiceId = null
        listeningCorrectCount = 0
    }

    /**
     * Reconstructs an interrupted session from SQLite answers plus a small
     * SharedPreferences pointer. Submitted answers are never replayed or lost;
     * an unsubmitted selection is restored only if it still belongs to the
     * first unanswered question.
     */
    private fun restoreListeningAttemptState() {
        val savedAttemptId = prefs.getLong(KEY_LISTENING_ATTEMPT_ID, -1L)
        val progress = (if (savedAttemptId > 0L) {
            listeningRepository.getAttemptProgress(savedAttemptId)
        } else null) ?: listeningRepository.getLatestIncompleteAttempt()
        if (progress == null) {
            if (savedAttemptId > 0L) clearActiveListeningAttempt()
            return
        }
        prefs.edit().putLong(KEY_LISTENING_ATTEMPT_ID, progress.id).apply()

        listeningAttemptId = progress.id
        listeningEpisodeId = progress.episodeId
        listeningCorrectCount = listeningRepository.getAttemptCorrectCount(progress.id)
        listeningSelectedChoiceId = null
        listeningSubmitted = false
        listeningCurrentCorrect = null
        listeningCorrectChoiceId = null

        if (progress.isCompleted) {
            listeningStage = "review"
            listeningQuestionIndex = 0
            return
        }
        if (progress.stage != "blind" && progress.stage != "quiz") {
            clearActiveListeningAttempt()
            return
        }

        listeningStage = progress.stage
        if (progress.stage == "blind") {
            listeningQuestionIndex = 0
            return
        }

        val questions = listeningRepository.getQuestionsForAttempt(progress.id)
        if (questions.isEmpty()) {
            clearActiveListeningAttempt()
            listeningStage = "home"
            return
        }
        val nextQuestionIndex = questions.indexOfFirst { question ->
            listeningRepository.getAttemptAnswer(progress.id, question.id) == null
        }
        if (nextQuestionIndex < 0) {
            listeningRepository.finishAttempt(progress.id)
            listeningStage = "review"
            listeningQuestionIndex = 0
            return
        }

        listeningQuestionIndex = nextQuestionIndex
        val savedIndex = prefs.getInt(KEY_LISTENING_QUESTION_INDEX, -1)
        val savedChoiceId = prefs.getLong(KEY_LISTENING_SELECTED_CHOICE_ID, -1L)
        if (savedIndex == nextQuestionIndex &&
            questions[nextQuestionIndex].options.any { it.id == savedChoiceId }
        ) {
            listeningSelectedChoiceId = savedChoiceId
        }
    }

    private fun resumeListeningAttempt(attemptId: Long) {
        if (listeningStageTransitioning) return
        val previousStage = listeningStage
        prefs.edit().putLong(KEY_LISTENING_ATTEMPT_ID, attemptId).apply()
        restoreListeningAttemptState()
        val restoredStage = listeningStage
        listeningStage = previousStage
        transitionListeningStage(restoredStage)
    }

    private fun renderListening(parent: LinearLayout) {
        when (listeningStage) {
            "intro" -> renderListeningIntro(parent)
            "blind" -> renderListeningBlind(parent)
            "quiz" -> renderListeningQuiz(parent)
            "review" -> renderListeningReview(parent)
            else -> renderListeningHome(parent)
        }
    }

    private fun renderListeningHome(parent: LinearLayout) {
        addSectionTitle(parent, "听力", "每日训练 · 听力库 · 专项精听")
        parent.addView(vSpace(16))
        val episode = listeningRepository.getEpisodeForDailyAssignment(java.time.LocalDate.now().toString(), listeningDailyEpisodeId)
            ?: listeningRepository.getEpisode(listeningDailyEpisodeId)
        if (episode == null) {
            val empty = card(18)
            empty.addView(text("听力课程暂不可用", 16f, ink, true))
            empty.addView(vSpace(7))
            empty.addView(text("课程数据尚未安装。后续内容包通过审核后会显示在这里。", 12f, secondary))
            parent.addView(empty)
            renderDiscoveredResourceLibrary(parent)
            return
        }

        val savedAttemptId = prefs.getLong(KEY_LISTENING_ATTEMPT_ID, -1L)
        val activeAttempt = if (savedAttemptId > 0L) {
            listeningRepository.getAttemptProgress(savedAttemptId)
        } else null
        val canResumeDaily = activeAttempt != null &&
            !activeAttempt.isCompleted && activeAttempt.episodeId == episode.id

        if (activeAttempt != null && !activeAttempt.isCompleted && activeAttempt.episodeId != episode.id) {
            val activeEpisode = listeningRepository.getEpisodeForAttempt(activeAttempt.id)
            if (activeEpisode != null) {
                val resumeCard = card(16)
                resumeCard.addView(text("未完成的训练", 11f, accent, true))
                resumeCard.addView(vSpace(5))
                resumeCard.addView(text(activeEpisode.title, 15f, ink, true))
                resumeCard.addView(vSpace(8))
                resumeCard.addView(pillButton("继续上次训练  →", false) {
                    resumeListeningAttempt(activeAttempt.id)
                })
                parent.addView(resumeCard)
                parent.addView(vSpace(10))
            }
        }

        val daily = card(24)
        daily.addView(text(if (episode.isDemo) "NO NEW COURSE" else "TODAY'S LISTENING", 11f, accent, true))
        daily.addView(vSpace(10))
        daily.addView(text(if (episode.isDemo) "暂无正式新课" else "今日新课", 16f, secondary, true))
        daily.addView(vSpace(7))
        daily.addView(text(episode.title, 24f, ink, true))
        daily.addView(vSpace(9))
        daily.addView(text(episode.description, 14f, secondary))
        daily.addView(vSpace(15))
        daily.addView(text("${episode.level}  ·  ${episode.categoryLabel}  ·  ${episode.durationLabel}", 13f, secondary))
        daily.addView(vSpace(16))
        daily.addView(pillButton(
            if (canResumeDaily) "继续上次训练  →"
            else if (episode.isDemo) "开始体验课  →" else "开始今日训练  →",
            true
        ) {
            if (canResumeDaily) {
                resumeListeningAttempt(activeAttempt!!.id)
            } else {
                transitionListeningStage("intro") {
                    listeningEpisodeId = episode.id
                    listeningArchiveAssignmentDate = null
                    listeningQuestionIndex = 0
                    listeningSelectedChoiceId = null
                    listeningSubmitted = false
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(daily)
        parent.addView(vSpace(10))
        renderDailyListeningArchive(parent)

        // Reviews are a separate queue: they never create or consume daily new-lesson assignments.
        // Never start a review while another attempt is unfinished; startAttempt
        // may otherwise abandon that work. The resume card takes priority.
        val dueReview = if (activeAttempt == null || activeAttempt.isCompleted) {
            listeningRepository.getDueReviewItems().firstOrNull()
        } else null
        val reviewEpisode = dueReview?.let { listeningRepository.getEpisode(it.episodeId) }
        if (dueReview != null && reviewEpisode != null) {
            val reviewCard = card(16)
            reviewCard.addView(text("SPACED REVIEW", 10f, accent, true))
            reviewCard.addView(vSpace(6))
            reviewCard.addView(text("薄弱课程复习", 15f, ink, true))
            reviewCard.addView(vSpace(5))
            reviewCard.addView(text(reviewEpisode.title, 14f, ink, true))
            reviewCard.addView(vSpace(5))
            reviewCard.addView(
                text(
                    "${reviewEpisode.level}  ·  上次正确率 ${(dueReview.lastScore * 100).toInt()}%  ·  已完成 ${dueReview.completedAttemptCount} 次",
                    11f,
                    secondary
                )
            )
            reviewCard.addView(vSpace(9))
            reviewCard.addView(pillButton("开始复习  →", false) {
                transitionListeningStage("intro") {
                    listeningEpisodeId = reviewEpisode.id
                    listeningArchiveAssignmentDate = null
                    listeningQuestionIndex = 0
                    listeningSelectedChoiceId = null
                    listeningSubmitted = false
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            parent.addView(reviewCard)
            parent.addView(vSpace(10))
        }

        val note = card(15)
        if (episode.isDemo) {
            note.addView(text("体验课说明", 13f, ink, true))
            note.addView(vSpace(5))
            note.addView(text("当前课程是原创短版演示材料，使用手机系统英语语音，不是真人录音，也不计入正式 5–10 分钟课程库。正式课程会在音频来源、授权和教学质量审核通过后逐步加入。", 11f, secondary))
        } else {
            note.addView(text("课程说明", 13f, ink, true))
            note.addView(vSpace(5))
            note.addView(text("本课程已通过内容完整性与播放授权检查。训练记录会单独保存，不会覆盖你的历史成绩。", 11f, secondary))
        }
        parent.addView(note)
        parent.addView(vSpace(20))

        addSectionTitle(parent, "听力库", "按主题与难度持续扩充")
        parent.addView(vSpace(9))
        val libraryEpisodes = listeningRepository.getEpisodes()
        if (libraryEpisodes.isEmpty()) {
            val emptyLibrary = card(16)
            emptyLibrary.addView(text("课程库正在准备中", 13f, secondary))
            parent.addView(emptyLibrary)
        } else {
            libraryEpisodes.forEach { libraryEpisode ->
                val libraryCard = card(16)
                val head = row()
                head.addView(
                    text(libraryEpisode.title, 15f, ink, true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                val saved = listeningRepository.isBookmarked(libraryEpisode.id)
                head.addView(quietButton(if (saved) "已收藏" else "收藏") {
                    listeningRepository.setBookmarked(libraryEpisode.id, !saved)
                    renderPage()
                })
                libraryCard.addView(head)
                libraryCard.addView(vSpace(5))
                libraryCard.addView(
                    text(
                        "${if (libraryEpisode.isDemo) "演示课程" else "正式课程"}  ·  ${libraryEpisode.categoryLabel}  ·  ${libraryEpisode.level}  ·  ${libraryEpisode.durationLabel}",
                        11f,
                        secondary
                    )
                )
                libraryCard.addView(vSpace(9))
                libraryCard.addView(pillButton("查看课程", false) {
                    transitionListeningStage("intro") {
                        listeningEpisodeId = libraryEpisode.id
                        listeningQuestionIndex = 0
                        listeningSelectedChoiceId = null
                        listeningSubmitted = false
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                parent.addView(libraryCard)
                parent.addView(vSpace(8))
            }
        }
        parent.addView(vSpace(10))

        renderDiscoveredResourceLibrary(parent)
        parent.addView(vSpace(10))

        val stats = card(15)
        stats.addView(text("我的听力记录", 13f, ink, true))
        stats.addView(vSpace(6))
        stats.addView(text("已完成训练：${listeningRepository.completedAttemptCount()} 次", 12f, secondary))
        stats.addView(vSpace(3))
        stats.addView(text("保留每次答题记录，后续可用于分析主旨、细节、推断和语音辨识能力。", 11f, secondary))
        parent.addView(stats)
    }

    private fun renderDailyListeningArchive(parent: LinearLayout) {
        val today = java.time.LocalDate.now().toString()
        val entries = db.readableDatabase.rawQuery(
            """SELECT d.assignment_date,r.episode_id,r.title,r.level,r.category
               FROM listening_daily_assignments d
               JOIN listening_episode_revisions r
                 ON r.revision_id=d.content_revision_id
                AND r.episode_id=d.episode_id
               WHERE d.assignment_date <= ? AND d.content_revision_id IS NOT NULL
               ORDER BY d.assignment_date DESC LIMIT 30""",
            arrayOf(today)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    arrayOf(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4)
                    )
                )
            }
        }
        val archive = card(15)
        archive.addView(text("每日听力归档", 14f, ink, true))
        archive.addView(vSpace(4))
        archive.addView(text("按日期自动保存每日课程，包含今天；历史课程可随时重新训练且不会占用新课名额。", 12f, secondary))
        if (entries.isEmpty()) {
            archive.addView(vSpace(6))
            archive.addView(text("完成每日训练后，历史课程会自动出现在这里。", 11f, secondary))
        } else {
            entries.forEach { entry ->
                val date = entry[0]
                val episodeId = entry[1]
                archive.addView(vSpace(8))
                val line = row()
                line.addView(
                    text("${if (date == today) "今天" else date}  ·  ${entry[2]}", 14f, ink, true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                line.addView(quietButton("重听") {
                    transitionListeningStage("intro") {
                        listeningEpisodeId = episodeId
                        listeningArchiveAssignmentDate = date
                        listeningQuestionIndex = 0
                        listeningSelectedChoiceId = null
                        listeningSubmitted = false
                    }
                })
                archive.addView(line)
                archive.addView(text("${entry[3]}  ·  ${entry[4]}", 10f, secondary))
            }
        }
        parent.addView(archive)
        parent.addView(vSpace(10))
    }

    private fun renderDiscoveredResourceLibrary(parent: LinearLayout) {
        val discovery = ListeningDiscoveryRepository(db)
        val allResources = discovery.listResources(200).distinctBy { resource ->
            if (resource.contentSha256.isNotBlank()) "sha:${resource.contentSha256}" else "id:${resource.id}"
        }
        val playableResources = allResources.filter { resource ->
            resource.rightsStatus == "cleared" && resource.allowedActions.contains("offline_download")
        }
        val pendingReviewCount = allResources.count { it.rightsStatus == "review_required" }
        val resources = playableResources.filter { resource ->
            (!discoveredDownloadedOnly || (resource.status == "downloaded" && resource.localFilePath.isNotBlank() && java.io.File(resource.localFilePath).isFile)) &&
                (!discoveredFavoritesOnly || resource.pinned) &&
                (discoveredSearchQuery.isBlank() ||
                    resource.title.contains(discoveredSearchQuery, ignoreCase = true) ||
                    resource.creator.contains(discoveredSearchQuery, ignoreCase = true) ||
                    resource.provider.contains(discoveredSearchQuery, ignoreCase = true))
        }
        val downloaded = allResources.count { it.status == "downloaded" && it.localFilePath.isNotBlank() && java.io.File(it.localFilePath).isFile }
        val queued = allResources.count { it.status == "queued" || it.status == "downloading" }
        val failed = allResources.count { it.status == "failed" }
        val storageMb = ListeningStorageAccounting.diskUsage(java.io.File(filesDir, "listening-library")) / (1024L * 1024L)
        addSectionTitle(parent, "自动发现资源库", "应用联网寻找新英语听力 · Wi-Fi 下自动下载")
        parent.addView(vSpace(8))
        val summary = card(16)
        summary.addView(text("已保存 $downloaded 条  ·  下载中 $queued 条  ·  失败 $failed 条", 12f, secondary, true))
        summary.addView(vSpace(4))
        summary.addView(text("本地占用约 $storageMb / 512 MB  ·  优先保护收藏和已播放内容", 10f, secondary))
        summary.addView(vSpace(7))
        summary.addView(text("只展示已核验且明确允许离线保存的资源；另有 $pendingReviewCount 条候选仍在许可审核中，审核通过前不会伪装成可播放内容。原始录音不会自动变成正式课程。", 12f, secondary))
        summary.addView(vSpace(10))
        summary.addView(pillButton("立即搜索新资源", true) {
            ListeningDiscoveryScheduler.schedule(this)
            ListeningResourceAutomation.schedule(this)
            Toast.makeText(this, "已安排联网搜索；符合许可的音频将在 Wi-Fi 下自动下载", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        summary.addView(vSpace(7))
        summary.addView(pillButton("＋ 上传 / 导入自己的听力音频", false) {
            openListeningAudioPicker()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(summary)
        renderUserListeningUploads(parent)
        parent.addView(vSpace(6))
        val filters = row()
        filters.addView(quietButton(if (discoveredDownloadedOnly) "显示全部" else "只看已下载") {
            discoveredDownloadedOnly = !discoveredDownloadedOnly
            renderPage()
        })
        filters.addView(quietButton(if (discoveredFavoritesOnly) "取消收藏筛选" else "只看收藏") {
            discoveredFavoritesOnly = !discoveredFavoritesOnly
            renderPage()
        })
        filters.addView(quietButton(if (discoveredSearchQuery.isBlank()) "搜索" else "清除搜索") {
            if (discoveredSearchQuery.isNotBlank()) {
                discoveredSearchQuery = ""
                renderPage()
            } else {
                val searchInput = EditText(this).apply {
                    hint = "标题、作者或来源"
                    setSingleLine(true)
                    setText(discoveredSearchQuery)
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle("搜索听力资源")
                    .setView(searchInput)
                    .setPositiveButton("搜索") { _, _ ->
                        discoveredSearchQuery = searchInput.text.toString().trim()
                        renderPage()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        parent.addView(filters)
        parent.addView(vSpace(5))

        if (resources.isEmpty()) {
            val empty = card(15)
            if (allResources.isEmpty()) {
                empty.addView(text("正在建立资源索引", 13f, ink, true))
                empty.addView(vSpace(5))
                empty.addView(text("首次搜索需要联网。找到符合开放许可的英语录音后，会自动排队下载并保存在本机。", 11f, secondary))
            } else {
                empty.addView(text("没有符合当前筛选条件的资源", 13f, ink, true))
                empty.addView(vSpace(5))
                empty.addView(text("可以切换到全部资源，或等待下一轮自动发现。", 11f, secondary))
            }
            parent.addView(empty)
            return
        }

        resources.forEach { resource ->
            val local = resource.status == "downloaded" &&
                resource.localFilePath.isNotBlank() && java.io.File(resource.localFilePath).isFile
            val item = card(15)
            val titleRow = row()
            titleRow.addView(
                text(resource.title, 14f, ink, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            titleRow.addView(quietButton("来源") {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resource.canonicalUrl)))
                }.onFailure {
                    Toast.makeText(this, "无法打开来源页面", Toast.LENGTH_SHORT).show()
                }
            })
            titleRow.addView(quietButton("许可") {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resource.licenseUrl)))
                }.onFailure {
                    Toast.makeText(this, "无法打开许可页面", Toast.LENGTH_SHORT).show()
                }
            })
            titleRow.addView(quietButton(if (resource.pinned) "已收藏" else "收藏") {
                discovery.setPinned(resource.id, !resource.pinned)
                renderPage()
            })
            item.addView(titleRow)
            if (resource.creator.isNotBlank()) {
                item.addView(vSpace(4))
                item.addView(text(resource.creator, 11f, secondary))
            }
            item.addView(vSpace(5))
            val statusLabel = when {
                local -> "已下载 · 可离线播放"
                resource.rightsStatus == "review_required" -> "已发现 · 许可待核验"
                resource.status == "downloading" -> "正在下载"
                resource.status == "queued" -> "已排队 · 等待 Wi-Fi 可用"
                resource.status == "failed" -> "下载失败 · 等待重试"
                resource.status == "expired" -> "本地音频因空间管理已清理"
                resource.status == "downloaded" -> "本地文件缺失 · 正在校验修复"
                else -> "已发现 · 等待处理"
            }
            item.addView(text(statusLabel, 11f, if (local) accent else secondary, true))
            item.addView(vSpace(4))
            item.addView(text(
                listOf(resource.provider, resource.licenseName, resource.durationMs.takeIf { it > 0L }?.let { "${(it + 59_999L) / 60_000L} 分钟" })
                    .filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  "),
                10f, secondary
            ))
            if (resource.attribution.isNotBlank()) {
                item.addView(vSpace(3))
                item.addView(text(resource.attribution, 10f, secondary))
            }
            if (local) {
                item.addView(vSpace(8))
                item.addView(pillButton("播放 / 继续收听  →", false) {
                    playDiscoveredResource(resource)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                item.addView(vSpace(5))
                item.addView(quietButton("删除本地音频（保留记录）") {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("删除本地音频？")
                        .setMessage("只删除手机中的音频文件，保留资源信息、收藏状态和听到的位置。之后仍可在许可有效时重新下载。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ ->
                            val root = java.io.File(filesDir, "listening-library")
                            val rootPrefix = runCatching { root.canonicalPath.trimEnd(java.io.File.separatorChar) + java.io.File.separator }.getOrNull()
                            val file = runCatching { java.io.File(resource.localFilePath).canonicalFile }.getOrNull()
                            if (file == null || rootPrefix == null || !file.path.startsWith(rootPrefix)) {
                                Toast.makeText(this, "文件路径校验失败，未删除", Toast.LENGTH_SHORT).show()
                                return@setPositiveButton
                            }
                            val detached = discovery.removeLocalCopy(resource.id, resource.localFilePath)
                            if (!detached) {
                                Toast.makeText(this, "资源状态已变化，请刷新后重试", Toast.LENGTH_SHORT).show()
                                renderPage()
                                return@setPositiveButton
                            }
                            if (listeningMediaController?.currentMediaItem?.mediaId == "resource:${resource.id}") {
                                listeningMediaController?.stop()
                                listeningMediaController?.clearMediaItems()
                            }
                            if (!discovery.isLocalPathShared(resource.id, resource.localFilePath) && file.exists() && !file.delete()) {
                                Toast.makeText(this, "记录已移除，文件将在后台清理", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "已删除本地音频，学习记录已保留", Toast.LENGTH_SHORT).show()
                            }
                            renderPage()
                        }
                        .show()
                })
            } else if (resource.status in setOf("failed", "expired") && resource.rightsStatus == "cleared") {
                item.addView(vSpace(8))
                item.addView(pillButton("重新下载", false) {
                    if (discovery.requeueExpiredDownload(resource.id)) {
                        ListeningResourceAutomation.enqueueDownload(this, resource.id)
                        Toast.makeText(this, "已加入 Wi-Fi 下载队列", Toast.LENGTH_SHORT).show()
                        renderPage()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else if (resource.rightsStatus == "review_required") {
                item.addView(vSpace(5))
                item.addView(text("来源许可尚未满足自动离线保存条件，因此不会擅自下载。", 10f, secondary))
            }
            parent.addView(item)
            parent.addView(vSpace(7))
        }
    }

    private fun openListeningAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT_LISTENING_AUDIO)
    }

    private fun saveUserListeningUpload(uri: Uri) {
        // Copying and probing a user-selected file can take seconds (or minutes
        // for a large document provider stream). Never do this work on the UI thread.
        val mime = runCatching { contentResolver.getType(uri).orEmpty() }.getOrDefault("")
        if (mime.isNotBlank() && !mime.startsWith("audio/", ignoreCase = true)) {
            Toast.makeText(this, "请选择音频文件", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在导入音频，请稍候…", Toast.LENGTH_SHORT).show()
        val id = java.util.UUID.randomUUID().toString()
        val directory = java.io.File(filesDir, "user-listening")
        val worker = Thread({
            var destination: java.io.File? = null
            try {
                if (!directory.exists() && !directory.mkdirs()) {
                    throw java.io.IOException("无法创建本地听力目录")
                }
                val displayName = runCatching {
                    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
                        }.orEmpty()
                }.getOrDefault("")
                val extensionFromName = displayName.substringAfterLast('.', "").lowercase()
                    .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                val extension = when (mime.lowercase()) {
                    "audio/mpeg" -> "mp3"
                    "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
                    "audio/aac" -> "aac"
                    "audio/ogg", "audio/opus" -> "ogg"
                    "audio/wav", "audio/x-wav", "audio/vnd.wave" -> "wav"
                    "audio/flac", "audio/x-flac" -> "flac"
                    else -> extensionFromName ?: "audio"
                }
                val targetFile = java.io.File(directory, "$id.$extension")
                destination = targetFile
                val input = contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("无法读取所选音频")
                var copied = 0L
                input.use { source ->
                    java.io.FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            copied += count
                            require(copied <= MAX_USER_AUDIO_BYTES) { "音频文件不能超过 100 MB" }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                require(copied > 0L) { "音频文件为空" }
                val retriever = android.media.MediaMetadataRetriever()
                val durationMs = try {
                    retriever.setDataSource(targetFile.absolutePath)
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                } finally {
                    retriever.release()
                }
                require(durationMs > 0L) { "无法识别音频格式或音频时长" }
                val rawSuggestedTitle = displayName.ifBlank { uri.lastPathSegment.orEmpty() }
                    .substringAfterLast('/').substringAfterLast(':')
                    .ifBlank { "我的听力音频" }
                val suggestedTitle = rawSuggestedTitle.substringBeforeLast('.', rawSuggestedTitle)
                    .ifBlank { "我的听力音频" }
                listeningPlaybackHandler.post {
                    if (isFinishing || isDestroyed) {
                        destination?.delete()
                        return@post
                    }
                    val titleInput = EditText(this).apply {
                        setSingleLine(true)
                        setText(suggestedTitle)
                        hint = "给这段听力起个名称"
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("保存听力内容")
                        .setMessage("音频已完成校验并复制到本地。你可以为它设置一个便于查找的名称。")
                        .setView(titleInput)
                        .setNegativeButton("取消") { _, _ -> destination?.delete() }
                        .setPositiveButton("保存") { _, _ ->
                            val title = titleInput.text.toString().trim().ifBlank { "我的听力音频" }.take(100)
                            val records = readUserListeningUploads()
                            records.put(JSONObject().apply {
                                put("id", id)
                                put("title", title)
                                put("path", targetFile.absolutePath)
                                put("mime", mime)
                                put("size", copied)
                                put("duration_ms", durationMs)
                                put("added_at", System.currentTimeMillis())
                            })
                            prefs.edit().putString(KEY_USER_LISTENING_UPLOADS, records.toString()).apply()
                            Toast.makeText(this, "已加入我的听力库", Toast.LENGTH_SHORT).show()
                            renderPage()
                        }
                        .setOnCancelListener { destination?.delete() }
                        .show()
                }
            } catch (error: Exception) {
                destination?.delete()
                listeningPlaybackHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "导入失败：${error.message ?: "文件无法读取"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, "words-audio-import")
        worker.isDaemon = true
        worker.start()
    }

    private fun readUserListeningUploads(): JSONArray = runCatching {
        JSONArray(prefs.getString(KEY_USER_LISTENING_UPLOADS, "[]"))
    }.getOrDefault(JSONArray())

    private fun renderUserListeningUploads(parent: LinearLayout) {
        val records = readUserListeningUploads()
        val card = card(15)
        card.addView(text("我的上传", 14f, ink, true))
        card.addView(vSpace(4))
        card.addView(text("可导入自己制作或获准使用的听力音频，包括通过 ChatGPT 等工具生成后保存的音频。文件仅保存在本机。", 11f, secondary))
        if (records.length() == 0) {
            card.addView(vSpace(6))
            card.addView(text("还没有上传内容。", 11f, secondary))
        } else {
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                val id = record.optString("id")
                val title = record.optString("title", "我的听力音频")
                val file = java.io.File(record.optString("path"))
                card.addView(vSpace(9))
                card.addView(text(title, 13f, ink, true))
                val durationMs = record.optLong("duration_ms", 0L)
                val durationLabel = if (durationMs > 0L) " · ${durationMs / 60_000L}分${(durationMs % 60_000L) / 1_000L}秒" else ""
                card.addView(text(
                    (if (file.isFile) "本地文件 · ${(file.length() / 1024L).coerceAtLeast(1L)} KB" else "文件缺失") + durationLabel,
                    10f, secondary
                ))
                if (file.isFile) {
                    card.addView(vSpace(5))
                    card.addView(pillButton("播放音频  →", false) {
                        val controller = listeningMediaController
                        if (controller == null) {
                            Toast.makeText(this, "播放器正在连接，请稍后再试", Toast.LENGTH_SHORT).show()
                        } else {
                            listeningPlaybackCoordinator.checkpoint(controller)
                            tts.stop()
                            val item = MediaItem.Builder()
                                .setMediaId("user-upload:$id")
                                .setUri(Uri.fromFile(file))
                                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("Words · 我的上传").build())
                                .build()
                            controller.setMediaItem(item)
                            controller.prepare()
                            controller.play()
                        }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                card.addView(vSpace(4))
                card.addView(quietButton("删除这条上传") {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("删除本地音频？")
                        .setMessage("只删除你导入的音频文件及其本地记录。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ ->
                            if (listeningMediaController?.currentMediaItem?.mediaId == "user-upload:$id") {
                                listeningMediaController?.stop()
                                listeningMediaController?.clearMediaItems()
                            }
                            if (file.exists() && !file.delete()) {
                                Toast.makeText(this, "无法删除音频文件，请检查存储空间后重试", Toast.LENGTH_LONG).show()
                                return@setPositiveButton
                            }
                            val remaining = JSONArray()
                            for (j in 0 until records.length()) {
                                val item = records.optJSONObject(j) ?: continue
                                if (item.optString("id") != id) remaining.put(item)
                            }
                            prefs.edit().putString(KEY_USER_LISTENING_UPLOADS, remaining.toString()).apply()
                            renderPage()
                        }
                })
            }
        }
        parent.addView(card)
    }

    private fun playDiscoveredResource(resource: DiscoveredListeningResource) {
        val file = java.io.File(resource.localFilePath)
        if (!file.isFile) {
            Toast.makeText(this, "本地音频文件不存在，等待重新下载", Toast.LENGTH_SHORT).show()
            return
        }
        val controller = listeningMediaController
        if (controller == null) {
            Toast.makeText(this, "播放器正在连接，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        listeningPlaybackCoordinator.checkpoint(controller)
        tts.stop()
        val progress = ListeningDiscoveryRepository(db).getResourcePlayback(resource.id)
        val item = MediaItem.Builder()
            .setMediaId("resource:${resource.id}")
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(resource.title)
                    .setArtist("Words · 自动发现资源")
                    .setExtras(Bundle().apply { putString("resource_id", resource.id) })
                    .build()
            )
            .build()
        val resumePosition = progress?.positionMs?.coerceAtLeast(0L) ?: 0L
        val safePosition = if (resource.durationMs > 0L && resumePosition >= resource.durationMs - 2_000L) 0L else resumePosition
        controller.setMediaItem(item, safePosition)
        controller.prepare()
        controller.setPlaybackSpeed(progress?.speed?.coerceIn(0.5f, 2f) ?: 1f)
        controller.play()
        Toast.makeText(this, "正在播放本地音频", Toast.LENGTH_SHORT).show()
    }

    private fun renderListeningIntro(parent: LinearLayout) {
        val episode = listeningRepository.getEpisodeForDailyAssignment(java.time.LocalDate.now().toString(), listeningEpisodeId)
            ?: listeningRepository.getEpisode(listeningEpisodeId)
            ?: return
        addSectionTitle(parent, "课程导入", "先了解场景，不提前展示原文")
        parent.addView(vSpace(14))
        val c = card(19)
        c.addView(text(episode.title, 21f, ink, true))
        c.addView(vSpace(8))
        c.addView(text(episode.description, 13f, secondary))
        c.addView(vSpace(13))
        c.addView(text("${episode.level}  ·  ${episode.categoryLabel}  ·  演示短课", 11f, accent, true))
        c.addView(vSpace(13))
        c.addView(text("训练流程", 14f, ink, true))
        c.addView(vSpace(5))
        c.addView(text("第一遍盲听 → 5 道理解题 → 逐句精听 → 原文翻译与语音解析 → 复盘。开始答题前不会显示全文。", 12f, secondary))
        c.addView(vSpace(16))
        c.addView(pillButton("开始第一遍盲听", true) {
            transitionListeningStage("blind") {
                val previousAttemptId = prefs.getLong(KEY_LISTENING_ATTEMPT_ID, -1L)
                if (previousAttemptId > 0L) {
                    val previousAttempt = listeningRepository.getAttemptProgress(previousAttemptId)
                    if (previousAttempt != null && !previousAttempt.isCompleted) {
                        listeningRepository.abandonAttempt(previousAttemptId)
                    }
                }
                listeningAttemptId = listeningRepository.startAttempt(episode.id, listeningArchiveAssignmentDate)
                listeningQuestionIndex = 0
                persistListeningAttemptUiState()
                listeningCorrectCount = 0
                listeningSelectedChoiceId = null
                listeningSubmitted = false
                listeningCurrentCorrect = null
                listeningCorrectChoiceId = null
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        c.addView(vSpace(5))
        c.addView(quietButton("返回听力首页") {
            listeningArchiveAssignmentDate = null
            transitionListeningStage("home")
        })
        parent.addView(c)
    }

    private fun renderListeningBlind(parent: LinearLayout) {
        val episode = listeningRepository.getEpisodeForAttempt(listeningAttemptId) ?: return
        val sentences = listeningRepository.getSentencesForAttempt(listeningAttemptId)
        addSectionTitle(parent, "第一遍 · 盲听", "先完整听，不看原文")
        parent.addView(vSpace(12))
        val c = card(19)
        c.addView(text(episode.title, 18f, ink, true))
        c.addView(vSpace(7))
        c.addView(text("请先听完整段落，尝试抓住主题、人物目的和关键变化。不要急着逐词翻译。", 12f, secondary))
        c.addView(vSpace(15))
        c.addView(text(if (episode.isDemo) "系统英语语音 · 演示模式" else "课程音频 · 正式训练", 11f, accent, true))
        c.addView(vSpace(9))
        c.addView(pillButton("▶  播放完整音频", true) {
            playListeningEpisode(episode)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        c.addView(vSpace(8))
        c.addView(pillButton("重新播放", false) {
            playListeningEpisode(episode)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addListeningSpeedControls(c, episode)
        c.addView(vSpace(14))
        c.addView(text("盲听阶段不会显示英文原文和中文翻译。播放完成后再进入理解测试。", 11f, secondary))
        c.addView(vSpace(13))
        c.addView(pillButton("我已完成初听，开始答题", true) {
            tts.stop()
            pauseListeningAudio()
            transitionListeningStage("quiz") {
                listeningRepository.updateAttemptStage(listeningAttemptId, "quiz")
                listeningQuestionIndex = 0
                listeningSelectedChoiceId = null
                listeningSubmitted = false
                persistListeningAttemptUiState()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        c.addView(vSpace(5))
        c.addView(quietButton("退出课程") {
            tts.stop()
            pauseListeningAudio()
            transitionListeningStage("home")
        })
        parent.addView(c)
    }

    private fun renderListeningQuiz(parent: LinearLayout) {
        if (listeningMediaController?.isPlaying == true) listeningMediaController?.pause()
        if (::tts.isInitialized) tts.stop()
        val episode = listeningRepository.getEpisodeForAttempt(listeningAttemptId) ?: return
        val questions = listeningRepository.getQuestionsForAttempt(listeningAttemptId)
        val question = questions.getOrNull(listeningQuestionIndex)
        if (question == null) {
            val firstUnanswered = questions.indexOfFirst { candidate ->
                listeningRepository.getAttemptAnswer(listeningAttemptId, candidate.id) == null
            }
            when {
                firstUnanswered >= 0 -> {
                    listeningQuestionIndex = firstUnanswered
                    renderListeningQuiz(parent)
                }
                questions.isNotEmpty() -> {
                    listeningRepository.finishAttempt(listeningAttemptId)
                    listeningStage = "review"
                    renderListeningReview(parent)
                }
                else -> {
                    clearActiveListeningAttempt()
                    listeningStage = "home"
                    renderListeningHome(parent)
                }
            }
            return
        }
        addSectionTitle(parent, "理解测试", "第 ${listeningQuestionIndex + 1} / ${questions.size} 题")
        parent.addView(vSpace(11))
        val progress = card(13)
        progress.addView(text("${question.typeLabel}  ·  ${episode.title}", 11f, secondary, true))
        progress.addView(vSpace(8))
        progress.addView(text(question.prompt, 20f, ink, true))
        progress.addView(vSpace(14))
        question.options.forEach { option ->
            val selected = listeningSelectedChoiceId == option.id
            val backgroundColor = if (listeningSubmitted && listeningCorrectChoiceId == option.id) softAccent else if (selected) 0xFFF1F3F7.toInt() else white
            val borderColor = if (listeningSubmitted && listeningCorrectChoiceId == option.id) accent else if (selected) 0xFFB9C2D6.toInt() else line
            val optionView = text(
                option.content,
                16f,
                if (listeningSubmitted && listeningCorrectChoiceId == option.id) accent else ink,
                selected || (listeningSubmitted && listeningCorrectChoiceId == option.id)
            ).apply {
                setPadding(dp(16), dp(17), dp(16), dp(17))
                background = rounded(backgroundColor, borderColor, 13)
                isEnabled = !listeningSubmitted
                isClickable = !listeningSubmitted
                setOnClickListener {
                    listeningSelectedChoiceId = option.id
                    persistListeningAttemptUiState()
                    renderPage()
                }
            }
            progress.addView(optionView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            progress.addView(vSpace(7))
        }

        if (!listeningSubmitted) {
            progress.addView(pillButton("提交答案", true) {
                val selectedId = listeningSelectedChoiceId
                if (selectedId == null) {
                    Toast.makeText(this, "请先选择一个选项", Toast.LENGTH_SHORT).show()
                } else {
                    val selected = question.options.firstOrNull { it.id == selectedId }
                    if (selected != null) {
                        listeningCurrentCorrect = listeningRepository.recordAnswer(
                            listeningAttemptId, question.id, selected.id
                        )
                        if (listeningCurrentCorrect == true) listeningCorrectCount += 1
                        listeningCorrectChoiceId = listeningRepository.getCorrectChoiceId(listeningAttemptId, question.id)
                        listeningSubmitted = true
                        persistListeningAttemptUiState()
                        renderPage()
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val evidence = listeningRepository.getSentencesForAttempt(listeningAttemptId).firstOrNull { it.id == question.evidenceSentenceId }
            progress.addView(vSpace(4))
            progress.addView(text(
                if (listeningCurrentCorrect == true) "回答正确" else "这题还需要加强",
                14f, if (listeningCurrentCorrect == true) accent else 0xFFB66B45.toInt(), true
            ))
            progress.addView(vSpace(6))
            progress.addView(text(question.explanation, 15f, secondary))
            if (evidence != null) {
                progress.addView(vSpace(7))
                progress.addView(text("音频原句：${evidence.english}", 15f, ink, true))
                progress.addView(vSpace(4))
                progress.addView(text("原句翻译：${evidence.chinese}", 14f, secondary))
            }
            progress.addView(vSpace(13))
            progress.addView(pillButton(
                if (listeningQuestionIndex == questions.lastIndex) "查看训练复盘" else "下一题  →",
                true
            ) {
                if (listeningQuestionIndex == questions.lastIndex) {
                    transitionListeningStage("review") {
                        listeningRepository.finishAttempt(listeningAttemptId)
                    }
                } else {
                    transitionListeningStage("quiz") {
                        listeningQuestionIndex += 1
                        listeningSelectedChoiceId = null
                        listeningSubmitted = false
                        listeningCurrentCorrect = null
                        listeningCorrectChoiceId = null
                        persistListeningAttemptUiState()
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        parent.addView(progress)
    }

    private fun addListeningSpeedControls(parent: LinearLayout, episode: ListeningEpisode) {
        if (episode.isDemo || episode.audioUrl.isNullOrBlank()) return
        parent.addView(vSpace(12))
        parent.addView(text("播放速度", 11f, secondary, true))
        parent.addView(vSpace(6))
        val speedRow = row()
        val currentSpeed = listeningPlaybackCoordinator.currentSpeed(listeningMediaController, episode)
        listOf(0.8f, 1.0f, 1.25f, 1.5f).forEach { speed ->
            val label = when (speed) {
                0.8f -> "0.8×"
                1.0f -> "1.0×"
                1.25f -> "1.25×"
                else -> "1.5×"
            }
            speedRow.addView(
                pillButton(label, kotlin.math.abs(currentSpeed - speed) < 0.01f) {
                    listeningPlaybackCoordinator.setSpeed(listeningMediaController, episode, speed)
                    renderPage()
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        parent.addView(speedRow)
    }

    private fun renderListeningReview(parent: LinearLayout) {
        val episode = listeningRepository.getEpisodeForAttempt(listeningAttemptId) ?: return
        val sentences = listeningRepository.getSentencesForAttempt(listeningAttemptId)
        val questions = listeningRepository.getQuestionsForAttempt(listeningAttemptId)
        val answerReviews = listeningRepository.getAttemptAnswerReviews(listeningAttemptId).associateBy { it.questionId }
        val persistedCorrectCount = answerReviews.values.count { it.isCorrect }
        val score = if (questions.isEmpty()) 0 else (persistedCorrectCount * 100 / questions.size)
        addSectionTitle(parent, "训练复盘", "把没听出来的内容真正听明白")
        parent.addView(vSpace(12))
        val result = card(18)
        result.addView(text("本次正确率", 12f, secondary, true))
        result.addView(vSpace(4))
        result.addView(text("$score%", 34f, accent, true))
        result.addView(vSpace(4))
        result.addView(text("${persistedCorrectCount} / ${questions.size} 道题答对", 12f, secondary))
        result.addView(vSpace(11))
        result.addView(text("成绩只记录本次作答，不覆盖此前训练记录。后续重做会产生一条新的训练记录。", 11f, secondary))
        parent.addView(result)
        parent.addView(vSpace(15))

        addSectionTitle(parent, "逐句精听", "逐句播放，先听声音，再核对意思")
        addListeningSpeedControls(parent, episode)
        sentences.forEachIndexed { index, sentence ->
            parent.addView(vSpace(8))
            val sentenceCard = card(14)
            val header = row()
            header.addView(text("0${index + 1}".takeLast(2), 11f, accent, true))
            header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            header.addView(quietButton("播放本句") {
                playListeningSentence(episode, sentence)
            })
            sentenceCard.addView(header)
            sentenceCard.addView(vSpace(5))
            sentenceCard.addView(text(sentence.english, 16f, ink, true))
            sentenceCard.addView(vSpace(5))
            sentenceCard.addView(text(sentence.chinese, 14f, secondary))
            if (sentence.pronunciationNote.isNotBlank()) {
                sentenceCard.addView(vSpace(7))
                sentenceCard.addView(text("语音提示：${sentence.pronunciationNote}", 11f, accent))
            }
            parent.addView(sentenceCard)
        }
        parent.addView(vSpace(15))
        addSectionTitle(parent, "答题解析")
        questions.forEachIndexed { index, question ->
            val answerCard = card(14)
            answerCard.addView(text("第 ${index + 1} 题 · ${question.typeLabel}", 11f, accent, true))
            answerCard.addView(vSpace(4))
            answerCard.addView(text(question.prompt, 13f, ink, true))
            answerCard.addView(vSpace(6))
            val answer = answerReviews[question.id]
            if (answer == null || answer.selectedContent.isNullOrBlank()) {
                answerCard.addView(text("本题未记录作答", 11f, secondary))
            } else {
                answerCard.addView(
                    text(
                        "你的选择：${answer.selectedContent}",
                        11f,
                        if (answer.isCorrect) accent else secondary,
                        answer.isCorrect
                    )
                )
                if (!answer.isCorrect) {
                    answerCard.addView(vSpace(3))
                    answerCard.addView(text("正确答案：${answer.correctContent}", 11f, accent, true))
                }
            }
            val evidence = sentences.firstOrNull { it.id == question.evidenceSentenceId }
            if (evidence != null) {
                answerCard.addView(vSpace(6))
                answerCard.addView(text("音频原句：${evidence.english}", 14f, ink, true))
                answerCard.addView(vSpace(4))
                answerCard.addView(text("原句翻译：${evidence.chinese}", 13f, secondary))
            }
            answerCard.addView(vSpace(6))
            answerCard.addView(text(question.explanation, 13f, secondary))
            parent.addView(vSpace(7))
            parent.addView(answerCard)
        }
        parent.addView(vSpace(15))
        parent.addView(pillButton("再练一次", true) {
            transitionListeningStage("blind") {
                listeningAttemptId = listeningRepository.startAttempt(episode.id, listeningArchiveAssignmentDate)
                listeningQuestionIndex = 0
                persistListeningAttemptUiState()
                listeningCorrectCount = 0
                listeningSelectedChoiceId = null
                listeningSubmitted = false
                listeningCurrentCorrect = null
                listeningCorrectChoiceId = null
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(vSpace(6))
        parent.addView(pillButton("返回听力首页", false) {
            tts.stop()
            pauseListeningAudio()
            transitionListeningStage("home") {
                clearActiveListeningAttempt()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        val browseSideCard = card(17)
        browseSideCard.addView(text("随刷默认正面", 15f, ink, true))
        browseSideCard.addView(vSpace(6))
        browseSideCard.addView(text("选择打开随刷时首先看到的语言；点击卡片即可翻到另一面，不再使用容易失效的遮挡模式。", 11f, secondary))
        browseSideCard.addView(vSpace(12))
        val firstSide = prefs.getString(KEY_BROWSE_FIRST_SIDE, "en") ?: "en"
        val sideRow = row()
        sideRow.addView(pillButton("英文优先", firstSide != "zh") {
            prefs.edit().putString(KEY_BROWSE_FIRST_SIDE, "en").apply()
            browseCardFlipped = false
            renderPage()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sideRow.addView(hSpace(8))
        sideRow.addView(pillButton("中文优先", firstSide == "zh") {
            prefs.edit().putString(KEY_BROWSE_FIRST_SIDE, "zh").apply()
            browseCardFlipped = false
            renderPage()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        browseSideCard.addView(sideRow)
        browseSideCard.addView(vSpace(10))
        addSwitchRow(browseSideCard, "随刷自动播放英文发音", "切换到新单词时自动调用系统英语语音。", KEY_AUTO_SPEAK, false)
        parent.addView(browseSideCard)
        parent.addView(vSpace(12))

        val quizModeCard = card(17)
        quizModeCard.addView(text("每日背词出题方向", 15f, ink, true))
        quizModeCard.addView(vSpace(6))
        quizModeCard.addView(text("混合练习会交替出现英文选中文、中文选英文。", 11f, secondary))
        quizModeCard.addView(vSpace(12))
        val quizMode = prefs.getString(KEY_QUIZ_DIRECTION, "mixed") ?: "mixed"
        quizModeCard.addView(pillButton("混合练习", quizMode == "mixed") {
            prefs.edit().putString(KEY_QUIZ_DIRECTION, "mixed").apply()
            resetCurrentQuizForSettings()
            renderPage()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        quizModeCard.addView(vSpace(8))
        quizModeCard.addView(pillButton("英文选中文", quizMode == "en_to_zh") {
            prefs.edit().putString(KEY_QUIZ_DIRECTION, "en_to_zh").apply()
            resetCurrentQuizForSettings()
            renderPage()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        quizModeCard.addView(vSpace(8))
        quizModeCard.addView(pillButton("中文选英文", quizMode == "zh_to_en") {
            prefs.edit().putString(KEY_QUIZ_DIRECTION, "zh_to_en").apply()
            resetCurrentQuizForSettings()
            renderPage()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(quizModeCard)
        parent.addView(vSpace(12))

        val feedbackCard = card(17)
        feedbackCard.addView(text("答题与翻页反馈", 15f, ink, true))
        feedbackCard.addView(vSpace(11))
        addSwitchRow(feedbackCard, "音效反馈", "答对、答错和滑动切换时播放轻提示音。", KEY_FEEDBACK_SOUND, false)
        addDivider(feedbackCard)
        addSwitchRow(feedbackCard, "震动反馈", "答题和滑动时提供轻微触感提示。", KEY_FEEDBACK_HAPTICS, true)
        parent.addView(feedbackCard)
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

        val onlineCard = card(17)
        onlineCard.addView(text("联网扩展词汇讲解", 15f, ink, true))
        onlineCard.addView(vSpace(6))
        onlineCard.addView(text("联网后补充英英释义、词形、例句和近反义词；查询结果会缓存到本机。", 11f, secondary))
        onlineCard.addView(vSpace(10))
        addSwitchRow(onlineCard, "允许联网查询", "关闭后只显示本地词书内容和已缓存资料。", KEY_ONLINE_DETAIL, true)
        parent.addView(onlineCard)
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
        about.addView(text("版本 1.6.4 · 释义排版与稳定性优化\n随刷与每日背词完全分离：随刷不记录熟悉度、不改变复习进度；背词保留每日目标与间隔复习。\n词频展示采用第三方考试与高中教材综合语料参考，不等同于官方历年高考完整词频。", 11f, secondary))
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
        browseCardFlipped = false
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
        browseCardFlipped = false
        saveBrowseState()
    }

    private fun saveBrowseState() {
        prefs.edit()
            .putString(KEY_BROWSE_BOOK, currentBookId)
            .putString(KEY_BROWSE_QUEUE, browseQueue.joinToString(","))
            .putInt(KEY_BROWSE_INDEX, browseIndex)
            .putString(KEY_BROWSE_RECENT, browseRecentIds.joinToString(","))
            .putInt(KEY_BROWSE_COUNT, browseCount)
            .putInt(KEY_BROWSE_QUEUE_VERSION, 2)
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
        // Rebuild the saved browse sequence once so users move from the old
        // one-appearance-per-cycle queue to the weighted repeat scheduler.
        if (prefs.getInt(KEY_BROWSE_QUEUE_VERSION, 0) < 2) {
            browseQueue.clear()
            browseIndex = 0
            prefs.edit().putInt(KEY_BROWSE_QUEUE_VERSION, 2).apply()
        }
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
        if (requestCode == REQUEST_IMPORT_LISTENING_AUDIO && resultCode == Activity.RESULT_OK) {
            data?.data?.let(::saveUserListeningUpload)
            return
        }
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
        private const val TAB_LISTENING = 2
        private const val TAB_BOOKS = 3
        private const val TAB_STATS = 4
        private const val TAB_SETTINGS = 5
        private const val REQUEST_IMPORT_PACK = 4102
        private const val REQUEST_IMPORT_LISTENING_AUDIO = 4103
        private const val KEY_USER_LISTENING_UPLOADS = "user_listening_uploads_v1"
        private const val MAX_USER_AUDIO_BYTES = 100L * 1024L * 1024L
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
        private const val KEY_BROWSE_FIRST_SIDE = "browse_first_side"
        private const val KEY_BROWSE_QUEUE_VERSION = "browse_queue_version"
        private const val KEY_QUIZ_DIRECTION = "quiz_direction"
        private const val KEY_ONLINE_DETAIL = "online_detail"
        private const val KEY_QUIZ_WORD_ID = "quiz_word_id"
        private const val KEY_QUIZ_SELECTED_ID = "quiz_selected_id"
        private const val KEY_QUIZ_CORRECT = "quiz_correct"
        private const val KEY_QUIZ_SHOW_EXPLANATION = "quiz_show_explanation"
        private const val KEY_FEEDBACK_SOUND = "feedback_sound"
        private const val KEY_FEEDBACK_HAPTICS = "feedback_haptics"
        private const val KEY_LISTENING_ATTEMPT_ID = "listening_active_attempt_id"
        private const val KEY_LISTENING_QUESTION_INDEX = "listening_question_index"
        private const val KEY_LISTENING_SELECTED_CHOICE_ID = "listening_selected_choice_id"
    }
}
