# words Android · v1.2.1

Native Android vocabulary app built with Kotlin, Android Views, SQLite and Android Text-to-Speech. Works offline after the wordbooks are installed.

## Two independent modes

### 随刷 — Quick Browse
- First tab and default entry, designed for quick idle-time use.
- Unlimited continuous browsing of the active wordbook.
- No familiarity questions and no rating buttons.
- Does not write review history or mastery, and does not consume the daily target.
- Keeps its own browse cursor, recent-view cooldown and browse count.
- Weighted without-replacement ordering; each full cycle covers every word once, without repeats.
- Recently viewed words are deferred to the end of the current cycle rather than permanently removed.
- Includes Android TTS, favorites, meanings and examples where available.

### 背词 — Daily Study
- A separate daily study area with its own queue and progress.
- Retains concealed/revealed cards, 不认识 / 模糊 / 认识 feedback, review history and statistics.
- Quick Browse never updates the study scheduler.

The modes share installed wordbook data, favorites and TTS, but not their queues, progress counters or mastery state.

## Wordbook importance and frequency

- `importance` preserves the source curriculum/list tier; it is not a historical exam-frequency count.
- `corpus_frequency` and `frequency_rank` are a third-party combined Gaokao-paper + high-school-textbook corpus reference.
- The external source is not an official or exhaustive Gaokao-only corpus. Unmatched words remain -1 / blank; missing data is not presented as zero occurrences.
- See `../wordbooks/FREQUENCY-REFERENCE.md` for the pinned source, license and limitations.
- Current core curriculum extraction: 2,974 entries. Approximately 126 entries remain to be reconciled against the stated ~3,100-word target. Extension book: 3,743 entries.

## Source structure

- `app/src/main/java/com/words/app/`: app screens, SQLite database, models and scheduler.
- `app/src/main/assets/books/`: offline dictionaries packaged in the app.
- `app/src/main/assets/licenses/`: third-party attribution and licenses.
- `core-tests/SchedulerSmokeTest.kt`: standalone JVM scheduler test.
- Repository root `android/`: canonical Android project; root `wordbooks/`: source dictionaries; root `tools/`: repeatable data and migration scripts.

## Build locally

Requirements: JDK 17, Android SDK Platform 35 / Build Tools 35.0.0, Gradle 8.9.

```bash
cd android
gradle --no-daemon clean assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

In v1.2.1, Quick Browse keeps its next-word action fixed above bottom navigation; the study reveal-selection card uses a shorter minimum height to avoid excess empty space.

Standalone scheduler check:

```bash
kotlinc app/src/main/java/com/words/app/Models.kt app/src/main/java/com/words/app/WeightedScheduler.kt core-tests/SchedulerSmokeTest.kt -include-runtime -d scheduler-test.jar
java -jar scheduler-test.jar
```

GitHub Actions builds the debug APK and publishes it in the `v1.2.1` Release. This APK is debug-signed for testing, not a Play Store production build.