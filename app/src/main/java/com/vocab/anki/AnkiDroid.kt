package com.vocab.anki

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Adds notes to AnkiDroid through its public content provider
 * (the same thing AnkiDroid's official "AddContentApi" library wraps).
 * Column names come from AnkiDroid's FlashCardsContract.
 */
class AnkiDroid(private val context: Context) {

    companion object {
        const val PACKAGE = "com.ichi2.anki"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        private const val AUTHORITY = "com.ichi2.anki.flashcards"
        private val DECKS: Uri = Uri.parse("content://$AUTHORITY/decks")
        private val MODELS: Uri = Uri.parse("content://$AUTHORITY/models")
        private val NOTES: Uri = Uri.parse("content://$AUTHORITY/notes")
        private const val FIELD_SEPARATOR = "\u001f"
        const val TAG = "vocab-app"

        /** The app's own note type. Each piece of the card is its own plain-text field. */
        const val NOTE_TYPE_NAME = "Vocab (Anki Adder)"
        private val FIELDS = listOf("Word", "Part of speech", "Definition", "Example")

        private const val FRONT = """<div class="word">{{Word}}</div>"""
        private const val BACK = """{{FrontSide}}
<hr id=answer>
{{#Part of speech}}<div class="pos">{{Part of speech}}</div>{{/Part of speech}}
<div class="def">{{Definition}}</div>
{{#Example}}<div class="ex">“{{Example}}”</div>{{/Example}}"""
        private const val CSS = """.card { font-family: sans-serif; font-size: 20px; text-align: center; color: #1b1d26; background: #ffffff; padding: 0 12px; }
.word { font-size: 34px; font-weight: bold; }
.pos { font-style: italic; color: #6b7080; margin-bottom: 8px; }
.def { line-height: 1.4; }
.ex { font-style: italic; color: #4a4f5e; margin-top: 16px; line-height: 1.4; }
.nightMode.card, .night_mode .card { color: #ecedf3; background: #1e2029; }
.nightMode .pos, .night_mode .pos, .nightMode .ex, .night_mode .ex { color: #a3a8ba; }"""
    }

    sealed class Result {
        data object Added : Result()
        data object AlreadyThere : Result()
        data class Failed(val message: String) : Result()
    }

    private val resolver get() = context.contentResolver

    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Adds one meaning of [word] to [deckName], creating the deck and note type if needed. */
    fun addWord(deckName: String, word: String, sense: Sense): Result {
        return try {
            if (isDuplicate(deckName, word)) return Result.AlreadyThere
            val deckId = findOrCreateDeck(deckName)
                ?: return Result.Failed("Couldn't find or create the \"$deckName\" deck.")

            val fields: List<String>
            val modelId: Long
            val own = findModel(NOTE_TYPE_NAME) ?: createNoteType(deckId)
            if (own != null) {
                modelId = own.first
                fields = listOf(word, sense.partOfSpeech, sense.definition, sense.example ?: "")
            } else {
                // Couldn't make our note type: fall back to Basic with plain text, no markup.
                val basic = findBasicModel() ?: return Result.Failed("No usable note type found in AnkiDroid.")
                modelId = basic.first
                val back = buildString {
                    append("(").append(sense.partOfSpeech).append(") ").append(sense.definition)
                    sense.example?.let { append(" — e.g. “").append(it).append("”") }
                }
                fields = List(basic.second.size) { i -> if (i == 0) word else if (i == 1) back else "" }
            }

            val values = ContentValues().apply {
                put("mid", modelId)
                put("flds", fields.joinToString(FIELD_SEPARATOR) { clean(it) })
                put("tags", TAG)
            }
            val noteUri = resolver.insert(NOTES, values)
                ?: return Result.Failed("AnkiDroid refused the new card.")

            // New cards land in the note type's default deck; move them to our deck.
            val cardsUri = Uri.withAppendedPath(noteUri, "cards")
            resolver.query(cardsUri, arrayOf("ord"), null, null, null)?.use { c ->
                val ordCol = c.getColumnIndexOrThrow("ord")
                while (c.moveToNext()) {
                    val cardUri = Uri.withAppendedPath(cardsUri, c.getString(ordCol))
                    resolver.update(cardUri, ContentValues().apply { put("deck_id", deckId) }, null, null)
                }
            }
            Result.Added
        } catch (e: SecurityException) {
            Result.Failed("Vocab isn't allowed to add cards yet. Allow it in AnkiDroid's permission prompt.")
        } catch (e: Exception) {
            Result.Failed("Couldn't add the card: ${e.message}")
        }
    }

    /** Fields are stored as HTML by Anki, so keep text plain: no tags, no stray angle brackets. */
    private fun clean(s: String) = s.replace("<", "‹").replace(">", "›").replace(FIELD_SEPARATOR, " ").trim()

    private fun findOrCreateDeck(name: String): Long? {
        resolver.query(DECKS, arrayOf("deck_id", "deck_name"), null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow("deck_id")
            val nameCol = c.getColumnIndexOrThrow("deck_name")
            while (c.moveToNext()) {
                if (c.getString(nameCol).equals(name, ignoreCase = true)) return c.getLong(idCol)
            }
        }
        val uri = resolver.insert(DECKS, ContentValues().apply { put("deck_name", name) })
        return uri?.lastPathSegment?.toLongOrNull()
    }

    private fun allModels(): List<Triple<Long, String, List<String>>> {
        val out = mutableListOf<Triple<Long, String, List<String>>>()
        resolver.query(MODELS, arrayOf("_id", "name", "field_names"), null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow("_id")
            val nameCol = c.getColumnIndexOrThrow("name")
            val fieldsCol = c.getColumnIndexOrThrow("field_names")
            while (c.moveToNext()) {
                out += Triple(c.getLong(idCol), c.getString(nameCol), c.getString(fieldsCol).split(FIELD_SEPARATOR))
            }
        }
        return out
    }

    private fun findModel(name: String): Pair<Long, List<String>>? =
        allModels().firstOrNull { it.second == name && it.third.size == FIELDS.size }?.let { it.first to it.third }

    private fun findBasicModel(): Pair<Long, List<String>>? {
        val candidates = allModels().filter { it.third.size >= 2 }
        val best = candidates.firstOrNull { it.second == "Basic" }
            ?: candidates.firstOrNull { it.second.startsWith("Basic") && !it.second.contains("reverse", true) }
            ?: candidates.firstOrNull()
        return best?.let { it.first to it.third }
    }

    /** Creates the "Vocab (Anki Adder)" note type with one card: word on the front, the rest on the back. */
    private fun createNoteType(deckId: Long): Pair<Long, List<String>>? = try {
        val modelUri = resolver.insert(MODELS, ContentValues().apply {
            put("name", NOTE_TYPE_NAME)
            put("field_names", FIELDS.joinToString(FIELD_SEPARATOR))
            put("num_cards", 1)
            put("css", CSS)
            put("deck_id", deckId)
            put("sort_field_index", 0)
        })
        val id = modelUri?.lastPathSegment?.toLongOrNull()
        if (modelUri != null && id != null) {
            val template = Uri.withAppendedPath(Uri.withAppendedPath(modelUri, "templates"), "0")
            resolver.update(template, ContentValues().apply {
                put("card_template_name", "Word → Meaning")
                put("question_format", FRONT)
                put("answer_format", BACK)
            }, null, null)
            id to FIELDS
        } else null
    } catch (e: Exception) {
        null
    }

    private fun isDuplicate(deckName: String, word: String): Boolean = try {
        val clean = word.replace("\"", "").replace("*", "").replace("_", "")
        // Matches cards from this note type ("Word") and older Basic cards ("Front").
        val search = "\"deck:$deckName\" (\"Word:$clean\" OR \"Front:$clean\")"
        resolver.query(NOTES, arrayOf("_id"), search, null, null)?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        false // If the search fails, just add the card.
    }
}
