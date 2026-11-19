package com.words.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Requests a short English-to-Chinese sentence translation.
 * MyMemory's anonymous use has a documented daily character limit; calls are
 * made only for a small number of example sentences and results are cached.
 * The result is labelled as machine-translation reference in the UI.
 */
object ExampleTranslator {
    private const val MAX_BYTES = 480

    fun translate(sentence: String): String? {
        val cleaned = sentence.trim()
        if (cleaned.isEmpty() || cleaned.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null

        val query = URLEncoder.encode(cleaned, "UTF-8")
        val endpoint = URL("https://api.mymemory.translated.net/get?q=$query&langpair=en%7Czh-CN")
        var connection: HttpURLConnection? = null
        return try {
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6500
                readTimeout = 8000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "words-android/1.5.0")
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) return null
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(raw)
            if (root.optInt("responseStatus", 0) !in 200..299) return null
            val value = root.optJSONObject("responseData")?.optString("translatedText")?.trim()
            value?.takeIf { it.isNotBlank() && !it.equals(cleaned, ignoreCase = true) }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
