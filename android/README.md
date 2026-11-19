# words Android · v1.4.0

Kotlin + Android Views + SQLite vocabulary app. Offline wordbooks remain the source of truth; online dictionary data is optional enrichment.

## Learning modes

### 随刷 — Quick Browse
- Unlimited weighted browsing, no familiarity rating, no daily quota and no review-history writes.
- Configurable masking: none, hide English, or hide Chinese. Only one side is hidden and can be revealed.
- Word/example playback via Android TextToSpeech; favorite action retained.

### 背词 — Daily Quiz
- Uses the configured daily target.
- Four-choice English-to-Chinese or Chinese-to-English questions; mixed mode alternates direction.
- Instant answer feedback and a detail card after selection.
- Correct / incorrect answers are persisted once as review events; skipping does not create a correctness event.
- Session queue and answer state are persisted locally.

## Vocabulary detail

Local wordbook data is shown first: headword, phonetic, part of speech, Chinese meaning and local example.

When online enrichment is enabled, the app requests FreeDictionaryAPI.com / Wiktionary data and may show:
- English definitions and available Chinese translations
- Word forms
- Examples
- Synonyms and antonyms

Raw responses are cached in SQLite table `word_detail_cache` for subsequent offline viewing. Unavailable fields are omitted rather than generated. Online data displays attribution and source/license link (CC BY-SA 4.0).

## Speech and favorites

- Android TextToSpeech engine; US/UK preference and voice-data availability check.
- Word and sentence speech controls throughout browsing, quiz details and favorites.
- Favorites span all installed wordbooks and persist in SQLite.

## Permissions and network

The app requests only Android's normal `INTERNET` permission for HTTPS dictionary lookup. Local learning, question generation, review records, TTS and cached detail rendering remain available offline.

## Data notes

- Core 2025 curriculum extraction currently has 2,974 entries; ~126 remain to reconcile against the ~3,100 target.
- Third-party combined-corpus frequency is separate from curriculum importance and is not an official exhaustive Gaokao count.
- FreeDictionaryAPI data is sourced from Wiktionary under CC BY-SA 4.0; the app shows source attribution.
- Debug APK, not production store signing.

## Build

JDK 17, Android SDK Platform 35 / Build Tools 35.0.0, Gradle 8.9.

```bash
gradle --no-daemon clean assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
