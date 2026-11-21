package com.words.app

import java.security.MessageDigest

data class ListeningAssignmentHistory(
    val episodeId: String,
    val category: String,
    val level: String,
    val accent: String,
    val assignmentCount: Int = 1,
    val lastAssignedDate: String? = null
)

/**
 * Deterministic rotation across the complete eligible catalog.
 *
 * Every episode is assigned once before any episode receives its next
 * assignment. New episodes enter the current rotation with a count of zero.
 * Once a full pass is complete, the oldest-assigned episode starts the next
 * pass. Topic, accent and level balancing are tie-breakers, not substitutes
 * for completing a catalog pass.
 */
object ListeningDailyPlanner {
    fun choose(
        candidates: List<ListeningEpisode>,
        assignmentHistory: List<ListeningAssignmentHistory>,
        assignmentDate: String
    ): ListeningEpisode? {
        if (candidates.isEmpty()) return null

        val historyById = assignmentHistory.associateBy { it.episodeId }
        val categoryCounts = assignmentHistory.groupingBy { it.category }
            .fold(0) { total, item -> total + item.assignmentCount }
        val accentCounts = recentHistory.groupingBy { it.accent.lowercase() }
            .fold(0) { total, item -> total + item.assignmentCount }
        val levelCounts = recentHistory.groupingBy { it.level }
            .fold(0) { total, item -> total + item.assignmentCount }
        val catalogRevision = candidates
            .map { "${it.id}:${it.updatedAt}:${it.audioUrl.orEmpty()}" }
            .sorted()
            .joinToString("|")
        val seed = "$assignmentDate|$catalogRevision"

        return candidates.minWithOrNull(
            compareBy<ListeningEpisode>(
                { historyById[it.id]?.assignmentCount ?: 0 },
                { historyById[it.id]?.lastAssignedDate.orEmpty() },
                { categoryCounts[it.category] ?: 0 },
                { accentCounts[it.accent.lowercase()] ?: 0 },
                { levelCounts[it.level] ?: 0 },
                { if (it.level == "L3" || it.level == "L4") 0 else 1 },
                { stableTieBreak("$seed|${it.id}") }
            )
        )
    }

    private fun stableTieBreak(value: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(8).fold(0L) { result, byte ->
            (result shl 8) or (byte.toLong() and 0xffL)
        }
    }
}
