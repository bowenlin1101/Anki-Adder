package com.vocab.anki

import android.text.TextUtils
import org.json.JSONArray
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Definition(val text: String, val example: String?)
data class Meaning(val partOfSpeech: String, val definitions: List<Definition>)
data class Entry(val word: String, val phonetic: String?, val meanings: List<Meaning>) {

    /** Short version read aloud: the first definition only. */
    fun spokenSummary(): String {
        val first = meanings.firstOrNull() ?: return word
        val def = first.definitions.firstOrNull()?.text ?: ""
        return "$word. ${first.partOfSpeech}: $def"
    }

    /** HTML for the back of the Anki card (also used for the on-screen view). */
    fun toHtml(): String = buildString {
        for (m in meanings) {
            append("<p><b><i>").append(esc(m.partOfSpeech)).append("</i></b></p><ol>")
            for (d in m.definitions) {
                append("<li>").append(esc(d.text))
                if (!d.example.isNullOrBlank()) {
                    append("<br><i>&ldquo;").append(esc(d.example)).append("&rdquo;</i>")
                }
                append("</li>")
            }
            append("</ol>")
        }
    }

    private fun esc(s: String) = TextUtils.htmlEncode(s)
}

class WordNotFoundException(word: String) : Exception("Couldn't find a definition for \"$word\".")

/** Looks words up in the free dictionaryapi.dev service (no account or API key needed). */
object Dictionary {
    private const val MAX_PARTS_OF_SPEECH = 3
    private const val MAX_DEFS_PER_PART = 3

    fun lookup(word: String): Entry {
        val url = URL("https://api.dictionaryapi.dev/api/v2/entries/en/" +
                URLEncoder.encode(word.lowercase(), "UTF-8").replace("+", "%20"))
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode == 404) throw WordNotFoundException(word)
            val body = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: FileNotFoundException) {
                throw WordNotFoundException(word)
            }
            if (body.isBlank()) throw WordNotFoundException(word)
            return parse(body, word)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(json: String, fallbackWord: String): Entry {
        val entries = JSONArray(json)
        if (entries.length() == 0) throw WordNotFoundException(fallbackWord)

        val first = entries.getJSONObject(0)
        val word = first.optString("word", fallbackWord)
        var phonetic: String? = first.optString("phonetic").ifBlank { null }

        // The API can split one word over several entries; merge them by part of speech.
        val byPart = LinkedHashMap<String, MutableList<Definition>>()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            if (phonetic == null) {
                val ps = e.optJSONArray("phonetics") ?: JSONArray()
                for (p in 0 until ps.length()) {
                    val t = ps.getJSONObject(p).optString("text")
                    if (t.isNotBlank()) { phonetic = t; break }
                }
            }
            val meanings = e.optJSONArray("meanings") ?: continue
            for (m in 0 until meanings.length()) {
                val mo = meanings.getJSONObject(m)
                val part = mo.optString("partOfSpeech", "").ifBlank { "definition" }
                val list = byPart.getOrPut(part) { mutableListOf() }
                val defs = mo.optJSONArray("definitions") ?: continue
                for (d in 0 until defs.length()) {
                    if (list.size >= MAX_DEFS_PER_PART) break
                    val dobj = defs.getJSONObject(d)
                    val text = dobj.optString("definition")
                    if (text.isNotBlank()) {
                        list += Definition(text, dobj.optString("example").ifBlank { null })
                    }
                }
            }
        }
        val meanings = byPart.filterValues { it.isNotEmpty() }
            .entries.take(MAX_PARTS_OF_SPEECH)
            .map { Meaning(it.key, it.value) }
        if (meanings.isEmpty()) throw WordNotFoundException(fallbackWord)
        return Entry(word, phonetic, meanings)
    }
}

/** Turns things people say ("what does ubiquitous mean?") into the word itself. */
object SpokenQuery {
    private val patterns = listOf(
        Regex("""^what does (?:the word )?(.+?) mean$"""),
        Regex("""^what(?: is|'s|s) the (?:meaning|definition) of (?:the word )?(.+)$"""),
        Regex("""^(?:define|definition of|meaning of|look up) (?:the word )?(.+)$"""),
        Regex("""^what(?: is|'s|s) (?:an? |the )?(.+)$"""),
    )

    fun extractWord(spoken: String): String {
        val s = spoken.lowercase().trim().trimEnd('?', '.', '!').trim()
        for (p in patterns) {
            p.find(s)?.let { return it.groupValues[1].trim() }
        }
        return s
    }

    /** true = yes, false = no, null = couldn't tell. */
    fun parseYesNo(spoken: String): Boolean? {
        val words = spoken.lowercase().split(Regex("""[^a-z']+""")).filter { it.isNotEmpty() }.toSet()
        val no = setOf("no", "nope", "nah", "don't", "dont", "skip", "cancel", "stop", "not")
        val yes = setOf("yes", "yeah", "yep", "yup", "sure", "add", "ok", "okay", "please", "definitely", "absolutely", "save")
        return when {
            words.any { it in no } -> false
            words.any { it in yes } -> true
            else -> null
        }
    }
}
