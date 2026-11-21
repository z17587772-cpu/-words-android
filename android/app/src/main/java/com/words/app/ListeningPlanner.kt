package com.words.app

/**
 * Produces a repeatable daily set with variety across formats and topics.
 * Advanced is the default target; intermediate/expert items may be mixed in
 * when the catalog is too small to provide a varied Advanced-only set.
 */
object ListeningPlanner {
    fun createPlan(
        date: String,
        catalog: List<ListeningItem>,
        count: Int = 3,
        seed: Int = date.hashCode()
    ): DailyListeningPlan {
        require(count > 0)
        if (catalog.isEmpty()) return DailyListeningPlan(date, emptyList())

        val candidates = catalog
            .filter { it.isActive && it.audioUrl.startsWith("https://") }
            .sortedWith(
                compareBy<ListeningItem> { if (it.difficulty == ListeningDifficulty.ADVANCED) 0 else 1 }
                    .thenByDescending { it.publishedAt }
            )
        if (candidates.isEmpty()) return DailyListeningPlan(date, emptyList())

        val random = java.util.Random(seed.toLong())
        val remaining = candidates.toMutableList()
        val chosen = mutableListOf<ListeningItem>()
        val usedTypes = mutableSetOf<ListeningType>()
        val usedTopics = mutableSetOf<String>()

        repeat(count.coerceAtMost(remaining.size)) {
            val next = remaining.maxWithOrNull(
                compareBy<ListeningItem> {
                    (if (it.difficulty == ListeningDifficulty.ADVANCED) 5 else 0) +
                        (if (it.type !in usedTypes) 4 else 0) +
                        (if (it.topic.lowercase() !in usedTopics) 3 else 0) +
                        random.nextInt(3)
                }
            ) ?: return@repeat
            chosen += next
            usedTypes += next.type
            usedTopics += next.topic.lowercase()
            remaining.remove(next)
        }
        return DailyListeningPlan(date, chosen)
    }
}
