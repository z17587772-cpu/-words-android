package com.words.app

data class ListeningEpisode(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val level: String,
    val durationMs: Long,
    val accent: String,
    val speechRate: Float,
    val audioUrl: String?,
    val isDemo: Boolean,
    val updatedAt: Long = 0L
) {
    val categoryLabel: String
        get() = when (category) {
            "daily" -> "日常对话"
            "news" -> "新闻社会"
            "science" -> "科学科普"
            "story" -> "故事文化"
            "interview" -> "访谈演讲"
            "exam" -> "考试训练"
            "pronunciation" -> "语音精听"
            else -> "综合听力"
        }

    val durationLabel: String
        get() = if (durationMs <= 0L) "时长待定" else "约 ${(durationMs + 59_999L) / 60_000L} 分钟"
}

data class ListeningSentence(
    val id: String,
    val episodeId: String,
    val ordinal: Int,
    val startMs: Long,
    val endMs: Long,
    val english: String,
    val chinese: String,
    val pronunciationNote: String
)

data class ListeningOption(
    val id: Long,
    val content: String
)

data class ListeningQuestion(
    val id: String,
    val episodeId: String,
    val ordinal: Int,
    val type: String,
    val prompt: String,
    val explanation: String,
    val evidenceSentenceId: String?,
    val options: List<ListeningOption>
) {
    val typeLabel: String
        get() = when (type) {
            "main_idea" -> "主旨理解"
            "detail" -> "细节捕捉"
            "inference" -> "推断判断"
            "attitude" -> "态度与意图"
            "pronunciation" -> "语音辨识"
            "dictation" -> "听写辨音"
            "connected_speech" -> "连读弱读"
            "note_taking" -> "信息笔记"
            "shadowing" -> "跟读训练"
            else -> "综合理解"
        }
}

data class ListeningPlaybackState(
    val episodeId: String,
    val positionMs: Long,
    val speed: Float
)

data class ListeningAnswerReview(
    val questionId: String,
    val selectedChoiceId: Long?,
    val selectedContent: String?,
    val correctChoiceId: Long,
    val correctContent: String,
    val isCorrect: Boolean
)
