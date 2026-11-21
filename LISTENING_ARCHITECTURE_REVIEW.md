# Words Listening — Architecture Review & Engineering Decisions

Date: 2026-11-21
Target branch: `feature/listening-mvp`
Status: design review; no production merge authorized

## 1. Executive decision

Continue from PR #1 as the implementation baseline. Keep the existing stable `main` / v1.8.0 untouched.

PR #2 is closed and must not be merged as-is. Its separate `words_listening.db` design conflicts with PR #1's same-database schema. The single-database design is preferable for this app because `listening_vocab_links.word_id` must reference `words.id`; SQLite foreign keys cannot enforce a relationship across two independent database files. Keep listening tables namespaced and additive, and prove the v5→v6 migration with an actual pre-upgrade database fixture before release.

Do not combine PR #1 and PR #2 mechanically. Reuse ideas only after reconciling schema, ownership and migration behavior.

## 2. Findings from the current implementation

### Confirmed strengths
- Listening data is separated into namespaced tables and does not replace the vocabulary tables.
- The schema has source/license metadata, episode/sentence/question data, attempts, answers, playback state, bookmarks and vocabulary links.
- Media3 ExoPlayer + MediaSessionService is the right foundation for lock-screen/background playback.
- The blind-listen → comprehension questions → sentence review → recap sequence is a sound MVP learning loop.
- The existing project uses native Android Views. Do not introduce Compose solely for the listening feature; keep the UI toolkit consistent and extract module boundaries incrementally.

### Gaps to resolve before calling this production-ready
1. The bundled item is an 85-second system-TTS demo at L2, not an Advanced human-recorded course. Keep it visibly labelled as a demo and out of the formal daily curriculum.
2. Demo sentence timecodes are all zero. Sentence seek/repeat cannot be accurate until each production sentence has validated start/end timestamps.
3. The current question model exposes `isCorrect` on option objects. Keep answer keys in a repository/domain-only structure; send the UI answer choices without correctness flags until the answer is submitted. This reduces accidental answer leakage in the blind-test flow.
4. The demo seeding path assigns the same demo episode to each date. Replace it with a deterministic daily planner that selects only approved, fully validated production episodes, avoids recent repeats, and records the assignment once.
5. Playback tables exist, but persisted resume position, speed, segment repeat and A–B repeat still need end-to-end implementation and lifecycle testing.
6. The Activity currently owns a large amount of unrelated app state. Avoid a risky rewrite; move listening state and actions behind a dedicated controller/repository boundary first.
7. CI packaging is not equivalent to device validation. Test migration, playback interruption, process recreation, audio focus and the actual APK on physical Android hardware.

## 3. Architecture to converge on

Keep a pragmatic layered design:

- **UI:** existing native Views; a listening-only screen renderer consumes an immutable `ListeningUiState` and forwards user actions.
- **Coordinator / use cases:** `StartAttempt`, `SubmitAnswer`, `FinishAttempt`, `GetDailyAssignment`, `SavePlaybackState`, `ToggleBookmark`, `GetTargetedReview`.
- **Repository:** the only owner of listening database reads/writes and content-catalog queries.
- **Playback:** `MediaSessionService` owns ExoPlayer; Activity binds through `MediaController`. UI must not create a second player.
- **Storage:** retain the existing `WordsDatabase` for now so vocabulary links can use a real FK to `words.id`. Keep all listening tables additive and prefixed.
- **Content sync:** WorkManager performs constrained, retryable package download/import; UI reads only the local database and local audio files.

Prefer a single source of truth: UI reads local database state; network responses are validated and transactionally imported into local storage before becoming visible.

## 4. Content package contract

Use a versioned package manifest rather than hard-coded lesson insertion. Each package should contain:

- `schema_version`, `package_id`, `package_version`, `min_app_version`, `created_at`, `content_revision`
- episode stable ID, title, summary, topic, format, target level, accent, duration, learning objectives
- audio asset path, byte size, SHA-256, media type, language and optional loudness metadata
- sentence IDs and ordinal order, exact start/end milliseconds, English transcript, reviewed Chinese translation and pronunciation/connected-speech notes
- exactly five or more quality-reviewed questions with type, choices, answer key, explanation and evidence sentence ID
- source, rights holder, attribution, license identifier/URL, permitted actions, territory and expiry/term

Import rules:
- Reject unknown schema versions, duplicate IDs, missing fields, invalid timestamps, invalid question references, mismatched audio hashes and unapproved rights metadata.
- Validate all package contents before writing; import in one database transaction.
- Never delete user attempts, bookmarks or playback state during content updates.
- Use stable episode/sentence IDs. Content corrections should increment content revision and preserve historical attempts.
- Download to a temporary file, verify checksum, then atomically move into the managed audio directory.
- Keep the previous valid package available until the new package is fully verified; rollback on failure.
- Do not treat a public URL as permission to redistribute or bundle audio.

## 5. Listening pedagogy and product behavior

Keep the agreed product contract:
- Advanced is the default target; L1–L4 remain explicit progression levels.
- Balanced content mix: daily conversation, news/social topics, science, stories/culture, interviews/talks, exam-oriented listening and pronunciation.
- Both update rhythms: a stable daily assignment plus a weekly expansion of the reusable library.
- Short daily session: about 5–10 minutes; full guided session: about 15–25 minutes.
- Five core comprehension questions per episode, covering main idea, detail, inference and speaker attitude/intent, with pronunciation/phrase recognition where pedagogically justified.

Recommended learning loop:
1. Preview only topic, objective, estimated difficulty and duration; do not reveal transcript or translation.
2. First uninterrupted listen, with no transcript.
3. Five comprehension questions; retain first-attempt score separately from later practice.
4. Review incorrect answers with evidence sentence and concise explanation.
5. Sentence-level replay with transcript and translation revealed only after the blind phase.
6. Optional dictation for selected difficult sentences, then shadowing / repeat-after-audio.
7. End with a small vocabulary set linked to existing `words.id` records, not duplicated dictionary entries.
8. Schedule only weak sentences/skills for review; do not force the entire episode to repeat.

Do not make every lesson a multiple-choice quiz. Rotate exercise emphasis across gist, detail, inference, dictation, connected speech, note-taking and shadowing.

## 6. Daily planner requirements

- Only select `status='approved'`, non-demo episodes with verified audio, transcript, questions, timestamps and rights.
- Advanced-first, but allow a controlled level mix if the approved Advanced pool is temporarily too small.
- Balance category, topic, accent and speaker; avoid consecutive repeats and a configurable recent-history window.
- Make selection deterministic for a given date and catalog revision. Do not call random number generation from inside a comparator; precompute a stable tie-break score per candidate, then sort.
- Persist the date→episode assignment once. A later catalog update must not silently change a lesson already started.
- If there is no valid production content, show a transparent “content being prepared” state or a clearly labelled demo; never disguise demo material as the daily course.

## 7. Playback acceptance criteria

- One service-owned ExoPlayer and one MediaSession; correct notification and lock-screen controls.
- Audio focus, headphone unplug, Bluetooth route changes, incoming interruption and user pause/resume behave predictably.
- Save position and speed on pause, media transition, task removal and periodic safe checkpoints; restore after Activity recreation/process death.
- Sentence seek uses validated timestamps; repeat-current-sentence and A–B loop are cancellable and cannot leave stale Handler callbacks running.
- Playback speed changes preserve pitch; provide a restrained set of useful speeds.
- Network failure, expired URL and offline mode show a recoverable state. Downloaded content must play without network.
- Release service resources exactly once and test service recreation.

## 8. Quality gates

### Automated
- Unit tests for planner determinism, no-repeat rules, question scoring and package validation.
- Database migration test using a real schema-v5 fixture with existing books, words, reviews, history, favourites and browse state; assert every row remains intact after v6 upgrade.
- Repository tests for transactional import, rollback, stable IDs and answer recording.
- UI-flow tests proving transcript/translation/answer keys are not exposed before the blind phase is submitted.
- CI: clean build, wordbook validation, listening package validation, unit tests and APK artifact upload. PR workflows must be read-only and must never publish a release.

### Physical-device
- Android 13/14/15+ where available: background playback, lock screen, notification, Bluetooth/headphones, screen rotation, low-memory process recreation, offline playback and database upgrade.
- Confirm existing browse, flashcards, review queue, dictionary details and vocabulary data remain unchanged.
- Check battery/network behavior of weekly sync and user-controlled downloads.

### Release gate
Do not merge the listening feature or publish a release until all automated gates pass, a schema-v5 upgrade is verified, production audio rights and educational quality are reviewed, and physical-device tests pass. Bump versionCode/versionName only for a release candidate; keep the stable v1.8.0 baseline unchanged during feature work.

## 9. Prioritized implementation sequence

1. Add tests and a schema-v5 migration fixture; fix any migration or referential-integrity issues first.
2. Separate answer keys from UI-facing options; lock down blind-listen information visibility.
3. Extract listening state/actions from MainActivity without changing the visual design.
4. Complete MediaController lifecycle, persisted resume/speed, sentence repeat and A–B repeat.
5. Implement manifest parser, checksum validation, transactional import and safe rollback.
6. Implement deterministic Advanced-first daily planner and weekly WorkManager sync.
7. Build a small, fully reviewed pilot set before scaling toward the planned 30-day course / 60-episode library.
8. Add targeted review and vocabulary links using existing word IDs.
9. Complete physical-device testing, then prepare a versioned APK release candidate.

## 10. Reference principles

- Android architecture: layered data/UI boundaries, repositories, coroutines/flows and unidirectional state flow.
- Android offline-first: local database is the source of truth; sync updates local state; network is not a UI data source.
- Android Media3: player and MediaSession live inside MediaSessionService for background playback.
- Spaced repetition: use a tested scheduler as a later enhancement; do not invent a complex adaptive algorithm before reliable attempt data exists. FSRS can be evaluated after collecting sufficient user review history.
