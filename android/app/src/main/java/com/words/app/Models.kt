package com.words.app

data class VocabularyBook(
    val id: String,
    val name: String,
    val description: String,
    val version: Int,
    val wordCount: Int
)

data class VocabularyWord(
    val id: Long,
    val bookId: String,
    val word: String,
    val phonetic: String,
    val partOfSpeech: String,
    val meaning: String,
    val example: String,
    val exampleZh: String,
    val importance: Int,
    val mastery: Int = 0,
    val seenCount: Int = 0,
    val correctCount: Int = 0,
    val lastSeenAt: Long = 0L,
    val favorite: Boolean = false,
    val corpusFrequency: Int = -1,
    val frequencyRank: String = "",
    val frequencySource: String = ""
)

data class VocabularyPack(
    val id: String,
    val name: String,
    val description: String,
    val version: Int,
    val words: List<PackWord>,
    val contentSha256: String = ""
)

data class PackWord(
    val word: String,
    val phonetic: String,
    val partOfSpeech: String,
    val meaning: String,
    val example: String,
    val exampleZh: String,
    val importance: Int,
    val corpusFrequency: Int = -1,
    val frequencyRank: String = "",
    val frequencySource: String = ""
)
