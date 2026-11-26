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

    /** Adds the word to [deckName], creating the deck if it doesn't exist yet. */
    fun addWord(deckName: String, front: String, back: String): Result {
        return try {
            val (modelId, fieldNames) = findNoteType()
                ?: return Result.Failed("No \"Basic\" note type found in AnkiDroid.")
            if (isDuplicate(deckName, fieldNames.first(), front)) return Result.AlreadyThere
            val deckId = findOrCreateDeck(deckName)
                ?: return Result.Failed("Couldn't find or create the \"$deckName\" deck.")

            val fields = Array(fieldNames.size) { "" }
            fields[0] = front
            if (fields.size > 1) fields[1] = back

            val values = ContentValues().apply {
                put("mid", modelId)
                put("flds", fields.joinToString(FIELD_SEPARATOR))
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

    /** Prefers the built-in "Basic" note type; falls back to any note type with 2+ fields. */
    private fun findNoteType(): Pair<Long, List<String>>? {
        val candidates = mutableListOf<Triple<Long, String, List<String>>>()
        resolver.query(MODELS, arrayOf("_id", "name", "field_names"), null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow("_id")
            val nameCol = c.getColumnIndexOrThrow("name")
            val fieldsCol = c.getColumnIndexOrThrow("field_names")
            while (c.moveToNext()) {
                val fields = c.getString(fieldsCol).split(FIELD_SEPARATOR)
                if (fields.size >= 2) candidates += Triple(c.getLong(idCol), c.getString(nameCol), fields)
            }
        }
        val best = candidates.firstOrNull { it.second == "Basic" }
            ?: candidates.firstOrNull { it.second.startsWith("Basic") && !it.second.contains("reverse", true) }
            ?: candidates.firstOrNull()
        return best?.let { it.first to it.third }
    }

    private fun isDuplicate(deckName: String, firstField: String, word: String): Boolean = try {
        val clean = word.replace("\"", "").replace("*", "").replace("_", "")
        val search = "\"deck:$deckName\" \"$firstField:$clean\""
        resolver.query(NOTES, arrayOf("_id"), search, null, null)?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        false // If the search fails, just add the card.
    }
}
