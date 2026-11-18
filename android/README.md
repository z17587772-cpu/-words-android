# words — Android vocabulary app (V1.0 source)

A white-first, offline-first English vocabulary app for high-school learners. The project uses native Android Views + Kotlin + SQLite, with no third-party runtime libraries.

## Implemented in this source version

- Four main areas: Learn, Flashcards, Wordbooks, Statistics, Settings.
- A card starts with both English and Chinese hidden when auto-cover is enabled.
- The learner can reveal English first or Chinese first, then reveal the other side.
- After revealing both sides, the learner can rate the word as `不认识 / 模糊 / 认识`.
- Local SQLite storage for imported wordbooks, favorites, mastery levels, review history, and last-seen timestamps.
- Weighted random session queues sampled without replacement.
- A recent-word cooldown: up to the last 30 distinct reviewed words are held out of the next queue where possible. If the remaining pool is too small, the oldest words are gradually allowed back in.
- Importance-weighted sampling, with extra weight for weak/new words and a coverage boost for words not seen recently.
- JSON wordbook import through Android's system file picker. Imported books are stored locally and can be used offline.
- English pronunciation through the device's Android text-to-speech engine. Offline speech availability depends on whether the device has an offline English voice installed.
- User preferences for auto-cover, auto-pronunciation, first reveal side, and daily target.

## Vocabulary wordbooks (updated)

The repository now provides two preloaded offline wordbooks:

- **新课标核心词汇（2025修订）**: 2,974 deduplicated entries extracted from a machine-readable compilation of Appendix 2 of the *普通高中英语课程标准（2017年版2025年修订）*. The official standard specifies 3,100 words (1,600 from compulsory education, 500 for high-school compulsory, 1,000 for selective compulsory); the available machine-readable extraction yielded 2,974 distinct app entries. The remaining 126 are explicitly recorded as an extraction/reconciliation gap and are not silently filled with unrelated words.
- **高中扩展词库（3,743词）**: a separate, non-official extension wordbook built by deduplicating 6,008 source rows from KyleBing's high-school vocabulary list. Duplicate entries are merged to retain multiple Chinese glosses.

The official standard provides headwords and course markers, but does not supply phonetics or Chinese definitions. Curriculum importance weights reflect the printed markers (unmarked=2, *=4, **=5); these are curriculum tiers, **not** measured Gaokao past-paper frequencies. The extension book uses a neutral importance value of 3. Device Android Text-to-Speech provides pronunciation; this source package does not bundle third-party audio recordings.

The licensed vocabulary source is KyleBing/english-vocabulary, under BSD 3-Clause. Its license and attribution are included in `wordbooks/` in the GitHub repository and are copied into the APK assets during the cloud build. See `wordbooks/SOURCES.md` for provenance and limitations.

For a standalone local build, copy the JSON files from the repository's `wordbooks/` folder into `app/src/main/assets/books/` before running Gradle. The GitHub Actions workflow performs this step automatically.

## Weighted scheduling rule

The current implementation uses this provisional score for each candidate word:

`importance² × masteryFactor × coverageFactor × newWordBonus`

- Importance 1–5 has a squared effect, so more important words have a substantially higher selection chance.
- New / not remembered / uncertain words receive larger mastery factors than familiar words.
- The coverage factor increases with time since the word was last seen. This helps low-frequency words re-enter a future session rather than disappearing forever.
- Sampling is without replacement inside a session, so no word can occur twice in the same generated queue.
- Recent unique words are held out by the cooldown rule whenever the remaining pool allows it.

This provides weighted pseudo-random ordering and repeated exposure across different sessions without short-interval repetition. It does not fabricate a claim that a particular word has appeared a specific number of times in official exams.

## Build the APK

### Cloud build (recommended for this source package)

The GitHub repository includes a root `.github/workflows/android-apk.yml` workflow. It unpacks the source archive, installs the two JSON wordbooks and data notices, then builds the APK:

1. Open the repository's **Actions** tab.
2. Select **Build words APK**.
3. Choose **Run workflow** (or push to `main` / `master`).
4. When the job succeeds, open its run and download the `words-debug-apk` artifact.

The workflow installs JDK 17, Android SDK Platform 35 / Build Tools 35.0.0, and Gradle 8.9, runs the pure Kotlin scheduler smoke test, builds the debug APK, and uploads `app-debug.apk` as an artifact. The current ChatGPT execution container has no Android SDK and outbound DNS is unavailable, so the APK cannot be built locally in this container.

### Local build

1. Install Android Studio and Android SDK Platform 35 / Build Tools 35.
2. Open this `words-android` folder in Android Studio (the folder containing `settings.gradle.kts`).
3. Use Gradle 8.9 with Android Gradle Plugin 8.7.3 and Kotlin 2.0.21; JDK 17 is required by the Android Gradle Plugin.
4. Run `gradle --no-daemon assembleDebug` from the project root.
5. The debug APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

A debug APK is suitable for testing. A separately signed release build is needed before public distribution.

## Wordbook JSON format

```json
{
  "id": "my_high_school_book",
  "name": "我的高中词书",
  "description": "自定义词书",
  "version": 1,
  "words": [
    {
      "word": "abandon",
      "phonetic": "/əˈbændən/",
      "pos": "v.",
      "meaning": "放弃；抛弃",
      "example": "Never abandon your dreams.",
      "example_zh": "永远不要放弃梦想。",
      "importance": 5
    }
  ]
}
```

`importance` accepts 1–5; if omitted it defaults to 3. The app currently imports JSON files selected from local storage. The initial source version does not yet include a hosted online catalogue or cloud account/synchronization service.

## Core scheduler smoke test

The pure Kotlin scheduler can be run without Android:

```bash
kotlinc app/src/main/java/com/words/app/Models.kt \
  app/src/main/java/com/words/app/WeightedScheduler.kt \
  core-tests/SchedulerSmokeTest.kt -include-runtime -d scheduler-test.jar
java -jar scheduler-test.jar
```
