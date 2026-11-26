package com.vocab.anki

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import java.util.Locale

/**
 * Flow:
 *  1. Get a word: by voice (opening the app or tapping the mic), by typing,
 *     by highlighting text in another app, or by sharing text to Vocab.
 *  2. Look it up and show the definition. In voice mode, read it aloud and
 *     ask "Do you want to add it to your vocabulary list?", then listen for yes/no.
 *  3. On yes, add a card (front: word, back: definitions) to the "Vocabulary"
 *     deck in AnkiDroid.
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        const val DECK_NAME = "Vocabulary"
        private const val REQ_SPEAK_WORD = 1
        private const val REQ_SPEAK_ANSWER = 2
        private const val REQ_ANKI_PERMISSION = 3
        private const val UTT_ASK = "ask"
        private const val UTT_INFO = "info"
    }

    private lateinit var wordInput: EditText
    private lateinit var resultCard: LinearLayout
    private lateinit var wordTitle: TextView
    private lateinit var phonetic: TextView
    private lateinit var senseGroup: RadioGroup
    private lateinit var pickHint: TextView
    private lateinit var status: TextView
    private lateinit var askRow: LinearLayout

    private lateinit var anki: AnkiDroid
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: Pair<String, String>? = null

    private var current: Entry? = null
    private var voiceMode = false
    private var answerRetries = 0
    private var addAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        anki = AnkiDroid(this)

        wordInput = findViewById(R.id.wordInput)
        resultCard = findViewById(R.id.resultCard)
        wordTitle = findViewById(R.id.wordTitle)
        phonetic = findViewById(R.id.phonetic)
        senseGroup = findViewById(R.id.senseGroup)
        pickHint = findViewById(R.id.pickHint)
        status = findViewById(R.id.status)
        askRow = findViewById(R.id.askRow)
        findViewById<TextView>(R.id.deckLabel).text = "Adds words to your \"$DECK_NAME\" deck in AnkiDroid"

        findViewById<ImageButton>(R.id.micButton).setOnClickListener { listenForWord() }
        findViewById<Button>(R.id.yesButton).setOnClickListener { addCurrentWord() }
        findViewById<Button>(R.id.noButton).setOnClickListener { declined() }
        wordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                lookUp(wordInput.text.toString(), voice = false)
                true
            } else false
        }

        tts = TextToSpeech(this, this)
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val text = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (text != null) {
            lookUp(text, voice = false)
        } else if (intent.action == Intent.ACTION_MAIN) {
            // Opened from the icon or "Hey Google, open Vocab": ask for a word right away.
            listenForWord()
        }
    }

    // ---- Voice ----------------------------------------------------------------

    private fun listenForWord() {
        stopSpeaking()
        startRecognizer(REQ_SPEAK_WORD, "What word do you want to look up?")
    }

    private fun listenForAnswer() {
        startRecognizer(REQ_SPEAK_ANSWER, "Add it to your vocabulary list? Say yes or no.")
    }

    private fun startRecognizer(requestCode: Int, prompt: String) {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, requestCode)
        } catch (e: ActivityNotFoundException) {
            status.text = "Voice input isn't available on this phone. Type the word instead."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        if (resultCode != RESULT_OK || heard.isNullOrEmpty()) {
            // Cancelled or nothing heard: leave the screen for typing / tapping.
            if (requestCode == REQ_SPEAK_ANSWER && current != null) status.text = "Add it to your vocabulary list?"
            return
        }
        when (requestCode) {
            REQ_SPEAK_WORD -> {
                val word = SpokenQuery.extractWord(heard[0])
                wordInput.setText(word)
                lookUp(word, voice = true)
            }
            REQ_SPEAK_ANSWER -> {
                val answer = heard.firstNotNullOfOrNull { SpokenQuery.parseYesNo(it) }
                when (answer) {
                    true -> addCurrentWord()
                    false -> declined()
                    null -> if (answerRetries++ < 1) {
                        speak("Sorry, was that a yes or a no?", UTT_ASK)
                    } else {
                        status.text = "Add it to your vocabulary list?"
                    }
                }
            }
        }
    }

    override fun onInit(result: Int) {
        ttsReady = result == TextToSpeech.SUCCESS
        val t = tts ?: return
        if (ttsReady) {
            t.language = Locale.US
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                @Deprecated("Deprecated in Java") override fun onError(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == UTT_ASK) runOnUiThread { if (!isFinishing) listenForAnswer() }
                }
            })
            pendingSpeech?.let { speak(it.first, it.second) }
        } else {
            // No text-to-speech engine: fall back to listening straight away.
            if (pendingSpeech?.second == UTT_ASK) listenForAnswer()
        }
        pendingSpeech = null
    }

    private fun speak(text: String, id: String) {
        if (!ttsReady) {
            pendingSpeech = text to id
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun stopSpeaking() = tts?.stop()

    // ---- Lookup ---------------------------------------------------------------

    private fun lookUp(raw: String, voice: Boolean) {
        val word = raw.trim().trim('"', '\'', '.', ',', '!', '?', ':', ';').trim()
        if (word.isEmpty()) return
        voiceMode = voice
        answerRetries = 0
        current = null
        wordInput.setText(word)
        resultCard.visibility = View.GONE
        askRow.visibility = View.GONE
        status.text = "Looking up \"$word\"…"

        Thread {
            val result = runCatching { Dictionary.lookup(word) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                result.onSuccess { showEntry(it) }.onFailure { e ->
                    val msg = when (e) {
                        is WordNotFoundException -> e.message!!
                        else -> "Couldn't reach the dictionary. Check your internet connection."
                    }
                    status.text = msg
                    if (voiceMode) speak(msg, UTT_INFO)
                }
            }
        }.start()
    }

    private fun showEntry(entry: Entry) {
        current = entry
        wordTitle.text = entry.word
        phonetic.text = entry.phonetic ?: ""
        phonetic.visibility = if (entry.phonetic.isNullOrBlank()) View.GONE else View.VISIBLE
        showSenses(entry.senses)
        resultCard.visibility = View.VISIBLE
        askRow.visibility = View.VISIBLE
        status.text = "Add it to your vocabulary list?"
        if (voiceMode) {
            speak("${entry.spokenSummary(entry.senses.first())}. Do you want to add it to your vocabulary list?", UTT_ASK)
        }
    }

    /** Fills the list of meanings: part of speech, definition, and example (if the dictionary has one). */
    private fun showSenses(senses: List<Sense>) {
        senseGroup.removeAllViews()
        pickHint.visibility = if (senses.size > 1) View.VISIBLE else View.GONE
        val muted = getColor(R.color.muted)
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
        senses.forEachIndexed { i, sense ->
            val text = SpannableStringBuilder()
            text.append(sense.partOfSpeech, StyleSpan(Typeface.ITALIC), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(muted), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append("\n").append(sense.definition)
            sense.example?.let {
                val start = text.length
                text.append("\n“").append(it).append("”")
                text.setSpan(StyleSpan(Typeface.ITALIC), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(ForegroundColorSpan(muted), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(RelativeSizeSpan(0.92f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                tag = i
                this.text = text
                textSize = 17f
                setTextColor(getColor(R.color.text))
                setPadding(pad, pad, 0, pad)
                setLineSpacing(0f, 1.15f)
            }
            senseGroup.addView(rb, RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT))
            if (i == 0) rb.isChecked = true
        }
    }

    private fun selectedSense(entry: Entry): Sense {
        val checked = senseGroup.findViewById<RadioButton>(senseGroup.checkedRadioButtonId)
        return entry.senses.getOrNull(checked?.tag as? Int ?: 0) ?: entry.senses.first()
    }

    // ---- Anki -----------------------------------------------------------------

    private fun addCurrentWord() {
        val entry = current ?: return
        stopSpeaking()
        if (!anki.isInstalled()) {
            status.text = "AnkiDroid isn't installed. Install it from the Play Store, then try again."
            if (voiceMode) speak("You need to install AnkiDroid first.", UTT_INFO)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${AnkiDroid.PACKAGE}")))
            } catch (_: ActivityNotFoundException) {}
            return
        }
        if (!anki.hasPermission()) {
            addAfterPermission = true
            requestPermissions(arrayOf(AnkiDroid.PERMISSION), REQ_ANKI_PERMISSION)
            return
        }

        status.text = "Adding…"
        askRow.visibility = View.GONE
        val sense = selectedSense(entry)
        Thread {
            val result = anki.addWord(DECK_NAME, entry.word, sense)
            runOnUiThread {
                val msg = when (result) {
                    AnkiDroid.Result.Added -> "Added \"${entry.word}\" to $DECK_NAME."
                    AnkiDroid.Result.AlreadyThere -> "\"${entry.word}\" is already in $DECK_NAME."
                    is AnkiDroid.Result.Failed -> result.message
                }
                status.text = msg
                if (result is AnkiDroid.Result.Failed) askRow.visibility = View.VISIBLE
                if (voiceMode) speak(msg, UTT_INFO)
            }
        }.start()
    }

    private fun declined() {
        stopSpeaking()
        askRow.visibility = View.GONE
        status.text = "OK, not added."
        if (voiceMode) speak("Okay.", UTT_INFO)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_ANKI_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (addAfterPermission) addCurrentWord()
        } else {
            status.text = "Vocab needs permission to add cards to AnkiDroid."
        }
        addAfterPermission = false
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(wordInput.windowToken, 0)
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
