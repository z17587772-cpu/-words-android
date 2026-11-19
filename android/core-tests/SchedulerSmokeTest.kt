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
    check(browse.size > words.size) { "weighted browse should add repeat appearances" }
    check(browse.toSet().size == words.size) { "every word should still appear in each cycle" }
    check(browse.take(browse.size - 25).none { it in recent.take(25) }) {
        "recently browsed words should stay in the cooldown tail"
    }
    check(browse.takeLast(25).toSet() == recent.take(25).toSet())
    val highBrowseCount = browse.count { it <= 10 }
    val lowBrowseCount = browse.count { it > 10 }
    check(highBrowseCount * 9 > lowBrowseCount) {
        "high-priority words should receive substantially more appearances ($highBrowseCount vs $lowBrowseCount)"
    }
    println("PASS: study weighting, full browse coverage, weighted repeats, and recent-word cooldown.")
}
