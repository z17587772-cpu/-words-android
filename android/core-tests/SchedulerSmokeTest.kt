import com.words.app.VocabularyWord
import com.words.app.WeightedScheduler
import kotlin.random.Random

fun main() {
    val words = (1L..100L).map { id ->
        VocabularyWord(
            id = id, bookId = "test", word = "word$id", phonetic = "", partOfSpeech = "n.",
            meaning = "meaning", example = "", exampleZh = "", importance = if (id <= 10) 5 else 1,
            corpusFrequency = if (id <= 10) 1000 else 10,
            frequencyRank = if (id <= 10) "high" else "low"
        )
    }
    val recent = (1L..30L).toList()
    val study = WeightedScheduler.createQueue(words, recent, 30, now = 1_800_000_000_000L, random = Random(42))
    check(study.size == 30 && study.toSet().size == 30)
    check(study.none { it in recent.take(25) })

    var high = 0
    var low = 0
    repeat(10_000) { seed ->
        val sample = WeightedScheduler.createQueue(words, emptyList(), 1, now = 1_800_000_000_000L, random = Random(seed)).single()
        if (sample <= 10) high++ else low++
    }
    check(high > low) { "high-priority/high-frequency words should be favored" }

    val browse = WeightedScheduler.createBrowseQueue(words, recent, Random(42))
    check(browse.size == words.size && browse.toSet().size == words.size)
    check(browse.take(words.size - 25).none { it in recent.take(25) })
    check(browse.takeLast(25).toSet() == recent.take(25).toSet())
    println("PASS: separated study/browse queues, no in-cycle repeats, cooldown, and weighted priority ($high vs $low).")
}
