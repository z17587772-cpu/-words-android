package com.words.app

import java.security.MessageDigest

data class ListeningAssignmentHistory(
    val episodeId: String,
    val category: String,
    val level: String,
    val accent: String,
    val assignmentCount: Int = 1
)

/**
 * Deterministic selection of never-before-assigned daily lessons.
 *
 * An episode can enter the new-lesson queue only once. Previously assigned
 * episodes are never recycled as new lessons; review is a separate workflow.
 * Newly published episode IDs enter the unseen pool immediately. Topic,
 * accent and level balancing only order genuinely unseen candidates.
 */
object ListeningDailyPlanner {
    fun choose(
        candidates: List<ListeningEpisode>,
        assignmentHistory: List<ListeningAssignmentHistory>,
        assignmentDate: String
    ): ListeningEpisode? {
        if (candidates.isEmpty()) return null

        val historyById = assignmentHistory.associateBy { it.episodeId }
        val unseenCandidates = candidates.filterNot { historyById.containsKey(it.id) }
        if (unseenCandidates.isEmpty()) return null

        val categoryCounts = assignmentHistory.groupingBy { it.category }
            .fold(0) { total, item -> total + item.assignmentCount }
        val accentCounts = assignmentHistory.groupingBy { it.accent.lowercase() }
            .fold(0) { total, item -> total + item.assignmentCount }
        val levelCounts = assignmentHistory.groupingBy { it.level }
            .fold(0) { total, item -> total + item.assignmentCount }
        val catalogRevision = candidates
            .map { "${it.id}:${it.updatedAt}:${it.audioUrl.orEmpty()}" }
            .sorted()
            .joinToString("|")
        val seed = "$assignmentDate|$catalogRevision"

        return unseenCandidates.minWithOrNull(
            compareBy<ListeningEpisode>(
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
