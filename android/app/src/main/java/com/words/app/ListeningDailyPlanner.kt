package com.words.app

import java.security.MessageDigest

data class ListeningAssignmentHistory(
    val episodeId: String,
    val category: String,
    val level: String,
    val accent: String
)

/**
 * Deterministic, explainable ranking for the next unassigned day.
 * It favors L3/L4 content while rotating episode IDs, topics and accents.
 */
object ListeningDailyPlanner {
    fun choose(
        candidates: List<ListeningEpisode>,
        recentHistory: List<ListeningAssignmentHistory>,
        assignmentDate: String
    ): ListeningEpisode? {
        if (candidates.isEmpty()) return null
        val recentIds = recentHistory.map { it.episodeId }.toSet()
        val categoryCounts = recentHistory.groupingBy { it.category }.eachCount()
        val accentCounts = recentHistory.groupingBy { it.accent.lowercase() }.eachCount()
        val levelCounts = recentHistory.groupingBy { it.level }.eachCount()
        val catalogRevision = candidates
            .map { "${it.id}:${it.audioUrl.orEmpty()}" }
            .sorted()
            .joinToString("|")
        val seed = "$assignmentDate|$catalogRevision"

        return candidates.minWithOrNull(
            compareBy<ListeningEpisode>(
                { if (it.id in recentIds) 1 else 0 },
                { if (it.level == "L3" || it.level == "L4") 0 else 1 },
                { categoryCounts[it.category] ?: 0 },
                { accentCounts[it.accent.lowercase()] ?: 0 },
                { levelCounts[it.level] ?: 0 },
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
