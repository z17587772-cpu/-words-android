# Words Listening — Product & Delivery Roadmap

Updated: 2026-11-21
Target: `feature/listening-mvp` / preview build 1.8.1
Release status: **not production-ready; keep PR #1 draft**

## 1. Product direction

Turn the current listening preview into a dependable, offline-capable listening course system that complements the existing vocabulary workflow. Preserve the current Words app experience and data; listening must be additive, not a rewrite of the vocabulary product.

Confirmed product contract:
- Default target: Advanced; explicit progression levels L1–L4.
- Balanced topic mix across everyday conversation, news/social issues, science, stories/culture, interviews/talks, exam listening and pronunciation.
- Daily short practice: 5–10 minutes. Full guided session: 15–25 minutes.
- Around five core comprehension questions per episode.
- Both rhythms: a stable daily assignment and a weekly expansion of the reusable library.
- Complete learning loop: blind listen → comprehension → answer evidence and explanation → sentence-level listening → transcript/translation → dictation or shadowing → vocabulary links → targeted review.
- Demo TTS is useful for interaction testing only. It must never be represented as a human-recorded production lesson.

## 2. Latest code audit — confirmed findings

### Improvements already present
- Listening data lives in namespaced tables inside the existing database, preserving same-database foreign keys to `words.id`.
- The v5→v6 migration adds listening tables rather than replacing vocabulary tables.
- The UI-facing `ListeningOption` no longer contains answer correctness; answer evaluation is handled by the repository and correctness is shown after submission.
- Demo course is explicitly labelled as an experience lesson.
- CI checks the answer-key boundary and packages a PR Debug APK using read-only permissions.
- Preview build is versionCode 22 / versionName 1.8.1.

### Remaining issues confirmed by source inspection
1. **Migration proof is now under CI validation.** An Android instrumentation test creates a representative schema-v5 database, upgrades it through the real SQLiteOpenHelper path, and checks books, words, favorites, reviews, history, caches and foreign-key integrity. The emulator run must pass before treating migration as verified.
2. **Playback persistence is incomplete.** `listening_playback` and `savePlayback()` exist, but no complete read/restore path or lifecycle checkpoint path was found. Speed restoration is not wired to the MediaController.
3. **The service is only a foundation.** `MediaSessionService` correctly owns ExoPlayer and MediaSession, but the Activity still lacks end-to-end playback state observation, persistence, error recovery and tested interruption handling.
4. **TTS demo is not Media3 audio.** The demo uses Android TextToSpeech directly. It is acceptable as a clearly labelled preview, but cannot validate real recording, precise timestamps, lock-screen playback or sentence seeking.
5. **Demo timestamps are placeholders.** Every demo sentence has start/end time zero. Do not enable timestamp-based seek for those rows.
6. **The formal daily planner is not implemented.** The preview course must not be written into `listening_daily_assignments`. Future assignments must be created only from fully approved production content.
7. **Content package import is not implemented.** There is no complete manifest validator, checksum verification, transactional package import, atomic audio install or rollback flow.
8. **Course catalog is not production content.** There is one short system-TTS demo, not the agreed Advanced-first course library.
9. **Test coverage is still incomplete.** A v5→v6 Android instrumentation migration test has now been added and wired to an API 35 emulator CI step. Repository, content-import, playback lifecycle and UI-flow tests are still missing.
10. **PR branch is divergent.** At this review the feature branch is 34 commits ahead and 8 behind main. Do not merge/rebase blindly; inspect the eight mainline commits, especially Gradle and CI toolchain changes, and integrate deliberately.

## 3. Execution plan — ordered by risk

### P0 — Protect existing users and make the preview honest
- Keep PR #1 draft and keep stable main untouched.
- Ensure demo seeding never creates a formal daily assignment.
- [x] Add a schema-v5 fixture with representative books, words, favorites, review rows, history and cache rows.
- [x] Upgrade fixture through the actual SQLiteOpenHelper migration to v6; assert IDs, values and foreign-key integrity.
- [ ] Wait for the API 35 emulator instrumentation job to pass before calling the migration verified.
- [x] Test fresh install separately from upgrade.
- Add a rollback test for a failed migration/import.
- Keep demo transcript and answers hidden during blind listening; add a UI-flow regression test.

**Exit gate:** migration and fresh-install tests pass; no vocabulary data changes; demo cannot be mistaken for an approved course.

### P1 — Finish reliable playback before adding more content
- Keep one ExoPlayer + MediaSession owned by ListeningPlaybackService.
- Expose playback state, current item, duration, position, speed and errors through MediaController callbacks.
- Add a repository model for saved playback state and implement both save and restore.
- [x] Persist on Activity pause/destruction and item changes; [x] add service-owned five-second checkpointing for playback that outlives the Activity.
- [x] Restore speed and position for the same episode and URI; [ ] add an audio-revision fingerprint before package updates.
- Avoid writing every progress tick to SQLite; throttle writes to reduce storage churn.
- Implement sentence replay only when verified timestamps exist.
- Implement A–B and current-sentence repeat as cancellable playback-controller state, not stale Activity Handler callbacks.
- Test audio focus, Bluetooth/headphone unplug, notification/lock-screen controls, network loss, process recreation and service destruction.

**Exit gate:** playback resumes accurately after Activity recreation and process death; interruption behavior is predictable; no duplicate players or stale callbacks.

### P2 — Build a real content package pipeline
Use a versioned manifest containing:
- package schema/version, package ID, minimum app version and content revision;
- stable episode IDs, title, summary, topic, target level, accent, speaker, duration and objectives;
- audio path, byte size, SHA-256, MIME type, language and optional audio metadata;
- stable sentence IDs, order, start/end milliseconds, English transcript, reviewed Chinese translation and pronunciation notes;
- question type, prompt, choices, answer key, explanation and evidence sentence ID;
- source, rights holder, attribution, license, permitted actions, territory and term/expiry.

Validation/import:
- [x] Reject unsupported schema versions, duplicate IDs, malformed timestamps, missing evidence references, invalid question cardinality, unsafe paths, malformed audio metadata and insufficient rights metadata.
- [x] Extract ZIP archives into private staging with entry/path/duplicate/decompressed-size limits; verify audio bytes before installation.
- Validate the complete package before mutating the database.
- Import metadata in a single transaction.
- [x] Add a reusable audio integrity primitive that stages to a temporary file, verifies exact size/SHA-256, and atomically moves only verified audio. [ ] Integrate it with managed storage and the database transaction.
- Keep the last valid package until the new package is fully verified.
- Preserve attempts, bookmarks, playback state and historical content revisions.
- Use WorkManager for retryable, network-constrained weekly sync; the local database and verified local audio remain the source of truth.

**Exit gate:** corrupt or interrupted package installs leave the previous library playable and user records intact; a verified course works offline.

### P3 — Add the daily planner and meaningful review
- Only assign `status=approved`, non-demo lessons with complete transcript, questions, verified timestamps, playable audio and rights.
- Prefer Advanced while retaining controlled L1–L4 progression.
- Balance topic, format, accent and speaker; avoid recent repeats.
- Make assignment deterministic for date + catalog revision; precompute stable ranking values instead of random comparators.
- Persist assignments once. Never silently replace a started lesson after a catalog update.
- If no approved lesson exists, show a transparent “正式课程准备中” state and a separately labelled optional demo.
- Store first-attempt score separately from later practice.
- Build weak-skill review only after enough reliable data exists. Start with explainable rules; evaluate FSRS later rather than introducing a speculative algorithm immediately.
- Link vocabulary using existing `words.id`; do not duplicate dictionary entries.

**Exit gate:** deterministic assignment tests pass; no demo in formal daily schedule; review recommendations are explainable and do not overwrite first-attempt history.

### P4 — Expand and quality-review the course library
- Build a 30-day course plan and a reusable library target of 60 episodes, but publish only after rights and educational QA.
- Start with the agreed Advanced default and a balanced topic mix.
- Each episode should have approximately five varied questions; do not use five identical multiple-choice patterns every time.
- Rotate gist, detail, inference, speaker intention, dictation, connected speech, note-taking and shadowing.
- Require transcript-to-audio alignment, natural pacing, clear audio, reviewed translation, plausible distractors and evidence-backed explanations.
- Track speaker/accent diversity without making accent labels a proxy for quality.
- Do not bundle or redistribute third-party audio without explicit rights.

**Exit gate:** every published episode has a complete provenance record, license review, audio QA, transcript alignment and teaching review.

### P5 — UX polish, accessibility and release readiness
- Preserve Words visual language: white/light grey, restrained accent, clear hierarchy and calm motion.
- Make the daily action obvious, but distinguish “daily course”, “library”, “demo” and “downloaded/offline”.
- Provide stable loading, empty, offline, error and retry states.
- Avoid layout jumps when answers, transcripts or translations appear.
- Add accessible touch targets, scalable text, TalkBack labels and adequate contrast.
- Use a fixed private release signing key stored only in GitHub Actions secrets; never commit the keystore or passwords.
- Keep Debug artifacts for PR review; publish signed release APK only from a deliberate version tag after approval.
- Verify APK upgrade/signature compatibility and test on a physical device.

**Exit gate:** physical-device checklist passes; signed APK installs over the previous signed build without uninstalling; release workflow cannot be triggered by a PR.

## 4. CI and branch safety

- PR workflows: `contents: read`, validation/instrumentation tests only; APK packaging is paused and available only through manual workflow dispatch after release-candidate approval. No release publishing and no push to main.
- Release workflow: tag-only, protected environment, private signing secrets, explicit approval.
- Keep CI validation separate from production publishing.
- Before synchronizing the divergent feature branch, compare all mainline-only commits and resolve toolchain changes in a dedicated integration commit or branch.
- Do not merge PR #1 merely because GitHub reports it as mergeable.

## 5. Suggested implementation sequence

1. [x] Add v5→v6 migration fixture and preservation assertions; [ ] pass emulator CI.
2. [x] Remove demo assignment writes and add a CI regression guard.
3. [x] Implement playback state model + repository restore/save + Activity lifecycle checkpoints + service-owned background checkpointing; [ ] add audio revision matching and runtime interruption tests.
4. Add sentence timestamp validation and loop controller.
5. [x] Implement package manifest parser/validator, secure archive extraction and audio integrity primitives; [ ] implement revision-safe transactional importer.
6. Implement audio download/checksum/atomic install and offline playback.
7. Implement deterministic daily planner and library browsing.
8. Add targeted review and vocabulary linking.
9. Populate and QA licensed human-recorded content.
10. Complete UI/accessibility polish, signing, device tests and release.

## 6. Release definition

A release candidate is not “done” merely because Gradle produces an APK. It must satisfy all of:
- upgrade migration preserves existing vocabulary, favorites, history and study progress;
- automated tests pass;
- production courses have complete audio, timestamps, transcripts, questions and license provenance;
- background/lock-screen/offline playback works on physical devices;
- resume, speed and sentence controls behave consistently;
- fixed signing permits in-place upgrades;
- PR CI cannot publish a release;
- a signed APK and its checksum are attached to a tagged release.

Until these gates pass, label the APK as a preview build and keep the PR unmerged.


## 7. Work log — 2026-11-21

- Fixed a compile regression: LocalDate remained used by the daily-assignment lookup after its import had been removed.
- Added ListeningPlaybackState and a repository read path; invalid negative positions and non-finite speeds are normalized before persistence.
- Wired MediaController playback to restore saved position/speed for the same episode URI, with throttled five-second checkpoints and lifecycle saves on pause/destruction.
- Added instrumentation coverage for playback-state round trips, invalid-value normalization and the invariant that demo seeding never creates a formal daily assignment.
- Changed PR validation to run checks and emulator instrumentation only; removed APK packaging/artifact upload from the listening PR workflow.
- Disabled automatic APK builds on PRs and main pushes. The APK workflow remains manual-only until the feature is ready for final packaging.
- The earlier PR workflow attempt was cancelled while newer commits were being pushed; it is not evidence of a passing emulator test. A fresh instrumentation run is still required.

- Extracted Media3 resume/checkpoint policy from MainActivity into ListeningPlaybackCoordinator; added episode metadata for system media controls.
- Added schema-v1 manifest validation, provenance requirements, path traversal checks, audio size/hash checks, and tests for invalid references and atomic replacement safety.
- Wrote LISTENING_CONTENT_PACKAGE_SPEC.md. Import is deliberately not implemented yet because the database needs a revision-safe policy that preserves existing attempts and answers; SQLite REPLACE must not be used on parent content rows.
- Moved periodic checkpoint ownership into MediaSessionService to avoid duplicate Activity/service writes. Media items carry stable episode IDs and notification metadata; sentence playback keeps the same episode identity so background persistence can resolve it.
- Moved periodic checkpoint database reads/writes to a single-thread service executor; service teardown queues a final checkpoint before closing its database helper. Added a guard against late MediaController connection callbacks after Activity destruction.
- Added ZIP archive extraction into a fresh staging directory, allowing only manifest/audio entries, rejecting traversal/absolute/duplicate paths, and enforcing per-entry/total decompressed-size plus entry-count limits. Added instrumentation coverage for successful extraction and rollback cleanup on traversal failure.
