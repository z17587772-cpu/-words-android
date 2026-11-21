# words Listening module — first implementation slice

## Product contract

- Main difficulty path: L1 foundation, L2 high-school core, L3 integrated advancement, L4 challenge.
- Balanced content categories: daily conversation, news/social topics, science, stories/culture, interviews/talks, exams and pronunciation.
- Daily task + weekly content packages are the target operating model.
- Formal course target: 5–10 minutes of licensed/original audio and 15–25 minutes of guided practice.
- The bundled demo_learning_habits item is an original short demonstration using the device's system TextToSpeech. It is not a finished formal audio course and is labelled as a demo in the database and UI.

## Android implementation

- Native Views remain in use; the new tab is isolated behind TAB_LISTENING.
- Media3 ExoPlayer and MediaSessionService provide the background playback foundation.
- Existing SQLiteOpenHelper is retained. Schema v6 adds listening-only tables and does not modify vocabulary/review rows.
- Media3 is pinned to stable 1.9.4 for the first integration to reduce build-toolchain churn. Re-evaluate a Media3/Kotlin/AGP upgrade separately.
- ListeningRepository owns listening reads/writes. Vocabulary links reference words.id; dictionary records are not copied.

## Content rights and publishing

Every production episode must have a source record, license record, attribution, rights-holder, territory, expiry/term and explicit allowed actions. Do not bundle third-party audio merely because it can be played or downloaded from a website. Unverified content must remain outside the approved daily feed.

## Next engineering stages

1. Replace the TTS demonstration with quality-reviewed, licensed audio episodes and accurate sentence timecodes.
2. Add validated versioned content-package import, checksum verification and safe rollback.
3. Add true segment looping, AB repeat and persisted Media3 playback position/speed.
4. Add a daily assignment generator, weekly WorkManager sync and user-controlled offline downloads.
5. Add targeted review scheduling and link key listening vocabulary to existing word IDs.
6. Add device tests for lock-screen playback, process recreation, network loss, database upgrade and all existing vocabulary/browse flows.
