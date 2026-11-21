package com.words.app

/**
 * Listening content taxonomy. Keep content metadata explicit so the library can
 * be balanced across formats, themes and sources without hard-coded URLs.
 */
enum class ListeningType(val key: String, val label: String) {
    CONVERSATION("conversation", "对话"),
    INTERVIEW("interview", "访谈"),
    NEWS("news", "新闻"),
    LECTURE("lecture", "讲座"),
    STORY("story", "故事"),
    ACADEMIC("academic", "学术");

    companion object {
        fun fromKey(value: String): ListeningType =
            entries.firstOrNull { it.key == value } ?: CONVERSATION
    }
}

enum class ListeningDifficulty(val key: String, val label: String) {
    INTERMEDIATE("intermediate", "Intermediate"),
    ADVANCED("advanced", "Advanced"),
    EXPERT("expert", "Expert");

    companion object {
        fun fromKey(value: String): ListeningDifficulty =
            entries.firstOrNull { it.key == value } ?: ADVANCED
    }
}

data class ListeningItem(
    val id: String,
    val title: String,
    val description: String = "",
    val type: ListeningType,
    val difficulty: ListeningDifficulty = ListeningDifficulty.ADVANCED,
    val topic: String,
    val durationSeconds: Int,
    val audioUrl: String,
    val transcript: String = "",
    val translation: String = "",
    val sourceName: String,
    val sourceUrl: String,
    val licenseName: String,
    val licenseUrl: String,
    val contentVersion: Int = 1,
    val publishedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)

data class ListeningProgress(
    val itemId: String,
    val lastPositionMs: Long = 0L,
    val completed: Boolean = false,
    val attempts: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

data class DailyListeningPlan(
    val date: String,
    val items: List<ListeningItem>,
    val createdAt: Long = System.currentTimeMillis()
)
