package com.words.app

data class ListeningReviewCandidate(
    val episodeId: String,
    val lastCompletedAtMs: Long,
    val lastScore: Float,
    val completedAttemptCount: Int
)

data class ListeningReviewItem(
    val episodeId: String,
    val lastScore: Float,
    val completedAttemptCount: Int,
    val dueAtMs: Long
)

/**
 * A transparent, score-based review schedule.
 *
 * Reviews are deliberately independent from the one-time daily new-lesson queue.
 * The most recent completed attempt determines the next interval:
 * <40%: 1 day; <60%: 3 days; <80%: 7 days; otherwise: 14 days.
 */
object ListeningReviewPlanner {
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun intervalDays(score: Float): Int? = when {
        !score.isFinite() || score !in 0f..1f -> null
        score < 0.40f -> 1
        score < 0.60f -> 3
        score < 0.80f -> 7
        else -> 14
    }

    fun due(
        candidates: List<ListeningReviewCandidate>,
        nowMs: Long
    ): List<ListeningReviewItem> =
        candidates.asSequence()
            .filter {
                it.episodeId.isNotBlank() &&
                    it.lastCompletedAtMs > 0L &&
                    it.completedAttemptCount > 0
            }
            .mapNotNull { candidate ->
                val days = intervalDays(candidate.lastScore) ?: return@mapNotNull null
                val intervalMs = days * DAY_MS
                val dueAt = if (candidate.lastCompletedAtMs > Long.MAX_VALUE - intervalMs) {
                    Long.MAX_VALUE
                } else {
                    candidate.lastCompletedAtMs + intervalMs
                }
                ListeningReviewItem(
                    episodeId = candidate.episodeId,
                    lastScore = candidate.lastScore,
                    completedAttemptCount = candidate.completedAttemptCount,
                    dueAtMs = dueAt
                )
            }
            .filter { it.dueAtMs <= nowMs }
            .sortedWith(
                compareBy<ListeningReviewItem> { it.lastScore }
                    .thenBy { it.dueAtMs }
                    .thenBy { it.episodeId }
            )
            .toList()
}
