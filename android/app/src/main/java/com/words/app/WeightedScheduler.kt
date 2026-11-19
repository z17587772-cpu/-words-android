package com.words.app

import kotlin.math.ln
import kotlin.random.Random

/**
 * Study and Quick Browse deliberately use separate schedules.
 *
 * Study uses a weighted permutation without replacement, prioritizing weak,
 * important and overdue vocabulary. Quick Browse guarantees one appearance of
 * every eligible word, then adds an extra weighted sample and interleaves those
 * repeat appearances through the cycle. Thus high-priority words recur more
 * often without starving the rest of the book.
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
     * Creates a mixed-frequency browse cycle:
     * 1) all words are included once in a weighted random order;
     * 2) one-third as many bonus appearances are drawn with replacement;
     * 3) bonus appearances are interleaved so repeats are distributed throughout;
     * 4) recently browsed words remain in the cooldown tail and are not bonus-drawn.
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

        val base = weightedOrder(fresh, 0L, random, ::browseWeight)
        if (base.isEmpty()) return cooled.map { it.id }

        val bonusCount = if (fresh.size < 12) 0 else
            maxOf(20, fresh.size / 3).coerceAtMost(fresh.size)
        val insertions = Array(base.size + 1) { mutableListOf<Long>() }
        val recentBonusIds = mutableListOf<Long>()

        repeat(bonusCount) {
            val selected = weightedPick(fresh, recentBonusIds, random)
            val gap = random.nextInt(base.size + 1)
            insertions[gap].add(selected)
            recentBonusIds.add(selected)
            if (recentBonusIds.size > 12) recentBonusIds.removeAt(0)
        }

        val mixed = buildList(base.size + bonusCount + cooled.size) {
            for (index in 0..base.size) {
                addAll(insertions[index])
                if (index < base.size) add(base[index])
            }
            addAll(cooled.map { it.id })
        }
        return mixed
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

    private fun weightedPick(
        candidates: List<VocabularyWord>,
        recentIds: List<Long>,
        random: Random
    ): Long {
        val total = candidates.sumOf { word ->
            browseWeight(word, 0L) * if (word.id in recentIds) 0.12 else 1.0
        }
        var ticket = random.nextDouble() * total
        for (word in candidates) {
            ticket -= browseWeight(word, 0L) * if (word.id in recentIds) 0.12 else 1.0
            if (ticket <= 0.0) return word.id
        }
        return candidates.last().id
    }

    private fun frequencyFactor(rank: String): Double = when (rank.lowercase()) {
        "high" -> 1.65
        "medium" -> 1.25
        "low" -> 0.85
        else -> 1.0
    }

    private fun studyWeight(word: VocabularyWord, now: Long): Double {
        val importanceFactor = word.importance.coerceIn(1, 5).toDouble().let { it * it }
        val masteryFactor = when (word.mastery.coerceIn(0, 3)) {
            0 -> 1.55
            1 -> 2.35
            2 -> 1.45
            else -> 0.62
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
        return (importance * importance * frequencyFactor(word.frequencyRank) * favoriteBoost)
            .coerceAtLeast(0.01)
    }
}