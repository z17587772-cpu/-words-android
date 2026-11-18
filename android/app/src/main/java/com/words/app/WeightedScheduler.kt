package com.words.app

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
