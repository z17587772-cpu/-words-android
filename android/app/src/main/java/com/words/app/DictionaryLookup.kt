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
    val chineseTranslations: List<String>
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
                val label = if (tags != null) strings(tags).joinToString(", ") else ""
                if (value.isNotBlank()) forms.add(if (label.isBlank()) value else "$value（$label）")
            }

            val senses = mutableListOf<DictionarySense>()
            val sensesJson = entry.optJSONArray("senses") ?: JSONArray()
            for (s in 0 until sensesJson.length()) {
                val sense = sensesJson.optJSONObject(s) ?: continue
                val definition = sense.optString("definition").trim()
                if (definition.isBlank()) continue

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
                allSynonyms.addAll(synonyms)
                allAntonyms.addAll(antonyms)
                senses.add(DictionarySense(definition, examples, synonyms, antonyms, chinese.distinct()))
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
