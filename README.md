# Vocab: look up a word, add it to Anki

An Android app that looks up a word, reads the definition to you, asks
**"Do you want to add it to your vocabulary list?"**, and if you say yes,
adds a card to the **Vocabulary** deck in AnkiDroid.

The app lists the word's meanings (most common first) with the first one
selected. Tap a different one if it fits how you saw the word, then add it.
Each card holds **one** meaning:

- **Front:** the word
- **Back:** part of speech, the definition, and an example sentence (when the dictionary has one)

Cards use their own note type, **Vocab (Anki Adder)**, with plain-text fields
*Word*, *Part of speech*, *Definition* and *Example*. The app creates it the
first time. To change how cards look, edit that note type's card template in
Anki. Cards are tagged `vocab-app`. A word already in the deck isn't added
again, and the Vocabulary deck is created if it doesn't exist.

## Ways to use it

| How | What happens |
|---|---|
| **"Hey Google, open Vocab"** | The app opens and listens. Say a word ("ubiquitous") or a question ("what does ubiquitous mean?"). It reads out the first definition and asks if you want to add it. Answer **yes** or **no** out loud (or tap a different meaning first). |
| Tap the **Vocab** icon | Same as above. |
| **Highlight a word in any app** (Chrome, Kindle, messages…) | Tap **⋮ → Add to Vocabulary** in the pop-up menu. It shows the meanings with **Add to Vocabulary** / **No thanks** buttons (no talking). |
| **Share** text to Vocab | Same as highlighting. |
| Type in the search box | Same as highlighting. |

Google doesn't let apps take over "Hey Google, define X" itself, so the voice
phrase is "Hey Google, **open Vocab**", then you say the word.

## Install (one time)

1. Install **AnkiDroid** from the Play Store if you don't have it. Open it once,
   and sign in to AnkiWeb (Settings → Sync) if you want cards to sync to your
   other devices.
2. On your phone, open this repo on GitHub, go to [`release/Vocab.apk`](release/Vocab.apk)
   and tap **Download** (the ⋯ menu or the download icon).
3. Open the downloaded file. Android will ask you to allow your browser to
   "install unknown apps". Allow it, then tap **Install**.
   If Play Protect warns about an unknown developer, tap **More details → Install anyway**.
   (This is expected for any app that isn't from the Play Store.)
4. Open **Vocab**. The first time you add a word, Android asks:
   *"Allow Vocab to read and write to the AnkiDroid database?"* Tap **Allow**.

## Changing things

- **Deck name:** `DECK_NAME` in `app/src/main/java/com/vocab/anki/MainActivity.kt`.
- **How many meanings are listed:** `MAX_PARTS_OF_SPEECH` / `MAX_DEFS_PER_PART` in `Dictionary.kt`.
- **Card layout:** `FRONT`, `BACK` and `CSS` in `AnkiDroid.kt` (only used when the note type is first created; after that, edit it in Anki).
- **Phrases it understands:** `SpokenQuery` in `Dictionary.kt`.

## How it works

- Definitions come from the free [dictionaryapi.dev](https://dictionaryapi.dev/)
  (English, no account or API key needed). A few very common words with huge
  entries (for example "run") come back empty from that service, and the app
  says it couldn't find them.
- Speech uses Android's built-in voice typing and text-to-speech.
- Cards are added through AnkiDroid's public content provider, the same
  interface its official `AddContentApi` uses. If it can't create its own
  note type, it falls back to your **Basic** note type with plain text.

## Building

Requires JDK 17+ and the Android SDK (platform 35).

```bash
./gradlew testReleaseUnitTest assembleRelease
cp app/build/outputs/apk/release/app-release.apk release/Vocab.apk
```

The release build is signed with `app/vocab-release.keystore` (committed on
purpose) so each new build installs over the old one without losing anything.
