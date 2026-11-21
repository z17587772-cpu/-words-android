# words Listening module — first implementation slice

## Confirmed product contract

- Core difficulty: **Advanced**. The daily training path should default to Advanced material; easier and harder levels remain available as supporting choices.
- Listening library mix: **Balanced** across daily conversation, news/social topics, science, stories/culture, interviews/talks, exams and pronunciation. Avoid allowing one category or topic to dominate the library.
- Content updates: **Both** — refresh the daily assignment for regular training and expand the reusable listening library through weekly content packages.
- Formal course target: 5–10 minutes of licensed/original audio and 15–25 minutes of guided practice.
- The bundled `demo_learning_habits` item is an original short demonstration using device system TextToSpeech. It is not a finished formal audio course, is not a human recording and does not represent the Advanced production content standard.

## Android implementation

- Native Views remain in use; the new tab is isolated behind `TAB_LISTENING`.
- Media3 ExoPlayer and MediaSessionService provide the background playback foundation.
- Existing `WordsDatabase` is retained. Schema v6 adds listening-only tables and does not alter vocabulary/review rows.
- Media3 is pinned to stable 1.11.1. It requires compileSdk 36; the branch uses AGP 8.11.1, Kotlin Gradle Plugin 2.2.20 and Gradle 8.13. This combination also resolves the Kotlin metadata mismatch surfaced by the first CI attempt. Keep these versions pinned rather than using dynamic dependency versions.
- `ListeningRepository` owns listening reads/writes. Vocabulary links reference existing `words.id`; dictionary records are not copied.
- The first demo validates the guided flow only. The daily assignment generator and weekly content sync are still follow-up work.

## Training flow

1. Intro: show the topic and learning objective without exposing the transcript.
2. Blind listen: play the complete audio; keep transcript and translation hidden.
3. Comprehension: five questions covering main idea, detail, inference and speaker attitude/intent.
4. Sentence review: replay individual sentences, then reveal English, Chinese meaning and pronunciation notes.
5. Recap: show the score and explanations; preserve every attempt rather than overwriting earlier results.

## Content rights and publishing

Every production episode must have a source record, license record, attribution, rights-holder, territory, expiry/term and explicit allowed actions. Do not bundle third-party audio merely because it can be played or downloaded from a website. Unverified content must remain outside the approved daily feed. The TTS demo must remain clearly labelled as a demo.

## Next engineering stages

1. Complete CI compilation and resolve any Kotlin/Android API errors before merging.
2. Replace the TTS demonstration with quality-reviewed, licensed audio episodes and accurate sentence timecodes.
3. Add validated, versioned content-package import, checksum verification and safe rollback.
4. Add true segment looping, AB repeat and persisted Media3 playback position/speed.
5. Add the Advanced-first daily assignment generator, weekly WorkManager sync and user-controlled offline downloads.
6. Add targeted review scheduling and link key listening vocabulary to existing word IDs.
7. Add device tests for lock-screen playback, process recreation, network loss, database v5→v6 upgrade and all existing vocabulary/browse flows.

## Release gate

This PR is a draft implementation slice. It must not be merged or published as a production listening release until the build passes, the database upgrade is verified against an existing v5 install, the training flow is tested on a real Android device, and production audio rights and quality are reviewed.
