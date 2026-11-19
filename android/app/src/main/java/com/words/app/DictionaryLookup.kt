package com.words.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class DictionarySense(
    val definition: String,
    val examples: List<String>,
    val synonyms: List<String>,
    val antonyms: List<String>,
    val chineseTranslations: List<String>,
    val collocations: List<String> = emptyList()
)

data class DictionaryEntryDetail(
    val partOfSpeech: String,
    val pronunciations: List<String>,
    val forms: List<String>,
    val senses: List<DictionarySense>
)

data class DictionaryDetails(
    val word: String,
    val entries: List<DictionaryEntryDetail>,
    val synonyms: List<String>,
    val antonyms: List<String>,
    val sourceUrl: String,
    val licenseName: String,
    val licenseUrl: String
) {
    fun allExamples(limit: Int = 6): List<String> =
        entries.flatMap { entry -> entry.senses.flatMap { it.examples } }
            .distinct().filter { it.isNotBlank() }.take(limit)

    fun allForms(limit: Int = 12): List<String> =
        entries.flatMap { it.forms }.distinct().filter { it.isNotBlank() }.take(limit)

    fun allChineseTranslations(limit: Int = 12): List<String> =
        entries.flatMap { entry -> entry.senses.flatMap { it.chineseTranslations } }
            .distinct().filter { it.isNotBlank() }.take(limit)

    fun allCollocations(limit: Int = 12): List<String> =
        entries.flatMap { entry -> entry.senses.flatMap { it.collocations } }
            .distinct().filter { it.isNotBlank() }.take(limit)
}

/**
 * Optional online enrichment from FreeDictionaryAPI.com.
 *
 * The response is based on Wiktionary. The caller is responsible for caching
 * the raw response and showing the required CC BY-SA 4.0 attribution and source URL.
 */
object DictionaryLookup {
    private const val CONNECT_TIMEOUT_MS = 7000
    private const val READ_TIMEOUT_MS = 9000
    private const val MAX_RESPONSE_BYTES = 1_500_000

    fun fetchRaw(word: String): String? {
        val cleaned = word.trim()
        if (cleaned.isEmpty() || cleaned.length > 80) return null
        val encoded = URLEncoder.encode(cleaned, "UTF-8").replace("+", "%20")
        val url = URL("https://freedictionaryapi.com/api/v1/entries/en/$encoded?translations=true")
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "words-android/1.6.1")
                instanceFollowRedirects = true
            }
            val status = connection.responseCode
            if (status !in 200..299) return null
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            bytes.toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun parse(rawJson: String): DictionaryDetails? {
        return try {
        val root = JSONObject(rawJson)
        val entriesJson = root.optJSONArray("entries") ?: return null
        val entries = mutableListOf<DictionaryEntryDetail>()
        val allSynonyms = linkedSetOf<String>()
        val allAntonyms = linkedSetOf<String>()

        for (i in 0 until entriesJson.length()) {
            val entry = entriesJson.optJSONObject(i) ?: continue
            val language = entry.optJSONObject("language")?.optString("code").orEmpty()
            if (language.isNotBlank() && language != "en") continue

            val pronunciations = stringField(entry.optJSONArray("pronunciations"), "text")
            allSynonyms.addAll(strings(entry.optJSONArray("synonyms")))
            allAntonyms.addAll(strings(entry.optJSONArray("antonyms")))
            val forms = mutableListOf<String>()
            val formsJson = entry.optJSONArray("forms") ?: JSONArray()
            for (f in 0 until formsJson.length()) {
                val form = formsJson.optJSONObject(f) ?: continue
                val value = form.optString("word").trim()
                val tags = form.optJSONArray("tags")
                val tagValues = if (tags != null) strings(tags) else emptyList()
                val label = chineseFormLabel(tagValues)
                if (value.isNotBlank()) forms.add("$label：$value")
            }

            val senses = mutableListOf<DictionarySense>()
            val sensesJson = entry.optJSONArray("senses") ?: JSONArray()
            for (s in 0 until sensesJson.length()) {
                val sense = sensesJson.optJSONObject(s) ?: continue
                val definition = sense.optString("definition").trim()
                if (definition.isBlank()) continue

                val parsedSenses = parseSenseTree(sense)
                parsedSenses.forEach { parsed ->
                    allSynonyms.addAll(parsed.synonyms)
                    allAntonyms.addAll(parsed.antonyms)
                    senses.add(parsed)
                }
            }

            if (senses.isNotEmpty() || forms.isNotEmpty() || pronunciations.isNotEmpty()) {
                entries.add(
                    DictionaryEntryDetail(
                        partOfSpeech = entry.optString("partOfSpeech").trim(),
                        pronunciations = pronunciations,
                        forms = forms.distinct(),
                        senses = senses
                    )
                )
            }
        }

        if (entries.isEmpty()) return null
        val source = root.optJSONObject("source") ?: JSONObject()
        val license = source.optJSONObject("license") ?: JSONObject()
        DictionaryDetails(
            word = root.optString("word").ifBlank { entriesJson.optJSONObject(0)?.optString("word").orEmpty() },
            entries = entries,
            synonyms = allSynonyms.toList().take(30),
            antonyms = allAntonyms.toList().take(30),
            sourceUrl = source.optString("url").ifBlank { "https://en.wiktionary.org/wiki/${root.optString("word")}" },
            licenseName = license.optString("name").ifBlank { "CC BY-SA 4.0" },
            licenseUrl = license.optString("url").ifBlank { "https://creativecommons.org/licenses/by-sa/4.0/" }
        )
        } catch (_: Exception) {
            null
        }
    }

    private fun chineseFormLabel(tags: List<String>): String {
        val value = tags.joinToString(" ").lowercase().replace('_', ' ')
        return when {
            "past participle" in value -> "过去分词"
            "present participle" in value -> "现在分词"
            "third-person singular" in value || "third person singular" in value -> "第三人称单数"
            "past" in value || "preterite" in value -> "过去式"
            "plural" in value -> "复数"
            "comparative" in value -> "比较级"
            "superlative" in value -> "最高级"
            "gerund" in value -> "动名词"
            "infinitive" in value -> "不定式"
            "present" in value -> "一般现在时形式"
            "alternative" in value || "variant" in value -> "拼写变体"
            "feminine" in value -> "阴性形式"
            "masculine" in value -> "阳性形式"
            else -> "其他形式"
        }
    }

    /**
     * The API's documented sense model provides definitions, translations,
     * examples, synonyms, antonyms and nested subsenses. Read optional phrase
     * arrays defensively when present; otherwise the UI derives phrase snippets
     * from the sourced example sentences.
     */
    private fun parseSenseTree(sense: JSONObject): List<DictionarySense> {
        val result = mutableListOf<DictionarySense>()
        val definition = sense.optString("definition").trim()
        if (definition.isNotBlank()) {
            val examples = strings(sense.optJSONArray("examples"))
            val synonyms = strings(sense.optJSONArray("synonyms"))
            val antonyms = strings(sense.optJSONArray("antonyms"))
            val chinese = mutableListOf<String>()
            val translations = sense.optJSONArray("translations") ?: JSONArray()
            for (t in 0 until translations.length()) {
                val translation = translations.optJSONObject(t) ?: continue
                val code = translation.optJSONObject("language")?.optString("code").orEmpty()
                if (code == "zh" || code.startsWith("zh-")) {
                    val value = translation.optString("word").trim()
                    if (value.isNotBlank()) chinese.add(value)
                }
            }
            val collocations = (
                strings(sense.optJSONArray("collocations")) +
                strings(sense.optJSONArray("phrases")) +
                strings(sense.optJSONArray("phraseology"))
            ).distinct()
            result.add(
                DictionarySense(
                    definition, examples, synonyms, antonyms, chinese.distinct(), collocations
                )
            )
        }
        val subsenses = sense.optJSONArray("subsenses") ?: JSONArray()
        for (index in 0 until subsenses.length()) {
            val child = subsenses.optJSONObject(index) ?: continue
            result.addAll(parseSenseTree(child))
        }
        return result
    }

    private fun stringField(array: JSONArray?, field: String): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val value = item.optString(field).trim()
            if (value.isNotBlank()) out.add(value)
        }
        return out.distinct()
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i).trim()
            if (value.isNotBlank()) out.add(value)
        }
        return out.distinct()
    }
}
