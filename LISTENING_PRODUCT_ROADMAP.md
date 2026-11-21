# Words Listening — Product & Delivery Roadmap

Updated: 2026-11-21
Target: `feature/listening-mvp` / preview code line; APK packaging intentionally paused
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
- The v5→v7 migration adds listening tables and revision-aware playback metadata rather than replacing vocabulary tables.
- The UI-facing `ListeningOption` no longer contains answer correctness; answer evaluation is handled by the repository and correctness is shown after submission.
- Demo course is explicitly labelled as an experience lesson.
- PR CI checks the answer-key boundary, content/package safety assertions and Android instrumentation; APK packaging/artifact upload is intentionally disabled during feature development.
- Preview build is versionCode 22 / versionName 1.8.1.

### Remaining issues confirmed by source inspection
1. **Migration proof is now under CI validation.** An Android instrumentation test creates a representative schema-v5 database, upgrades it through the real SQLiteOpenHelper path, and checks books, words, favorites, reviews, history, caches and foreign-key integrity. The emulator run must pass before treating migration as verified.
2. **Playback persistence is incomplete.** `listening_playback` and `savePlayback()` exist, but no complete read/restore path or lifecycle checkpoint path was found. Speed restoration is not wired to the MediaController.
3. **The service is only a foundation.** `MediaSessionService` correctly owns ExoPlayer and MediaSession, but the Activity still lacks end-to-end playback state observation, persistence, error recovery and tested interruption handling.
4. **TTS demo is not Media3 audio.** The demo uses Android TextToSpeech directly. It is acceptable as a clearly labelled preview, but cannot validate real recording, precise timestamps, lock-screen playback or sentence seeking.
5. **Demo timestamps are placeholders.** Every demo sentence has start/end time zero. Do not enable timestamp-based seek for those rows.
6. **The formal daily planner is implemented, but production content import is not.** It assigns only approved, non-demo episodes with complete rights, audio, timestamps, evidence and answer-choice validation. With demo-only content it returns no formal assignment and the UI continues to label the demo explicitly.
7. **Content package import is still incomplete.** Manifest validation, safe ZIP staging and audio checksum/atomic-file primitives exist; transactional database import, revision snapshots, rollback and offline download orchestration are not implemented.
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
- [x] Make assignment deterministic for date + catalog revision using stable tie-breaks; prioritize L3/L4 while rotating recent episode IDs, topics and accents.
- [x] Persist each date's assignment once with a transaction and conflict-safe insert. Existing assignments are returned unchanged. [ ] Add catalog-update handling for started lessons.
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
7. [x] Implement deterministic approved-only daily planner, full lesson-library listing and explainable per-question answer review; [ ] add skill-based review recommendations.
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
- Added 0.8×/1.0×/1.25×/1.5× speed selection for approved course audio in blind-listening and sentence-review stages; the selected speed is persisted and restored. The system-TTS demo intentionally does not expose a fake Media3 speed control.
- Diagnosed the failed emulator CI attempt: the GitHub runner has no hardware CPU accelerator, so the emulator exited before tests ran (`Software CPU emulation (-accel off) is not supported`). Updated the emulator job to opt into TCG software emulation and disable animations. This was an infrastructure failure, not a test assertion or Kotlin compilation result; the latest code still requires a fresh successful validation run.
- Added deterministic daily assignment selection with advanced-first prioritization, recent-repeat avoidance, and topic/accent rotation. Eligibility checks require approved non-demo audio, valid source/license provenance, unexpired playback rights, complete chronological sentence timing, and 3-8 fully evidenced questions with 2-6 choices and exactly one correct answer. Assignment writes are transactional and idempotent.
- Added instrumentation coverage for advanced-first selection, repeat avoidance, stable same-day assignments, and the empty-catalog case where demo content must not become a formal assignment.
- Expanded post-quiz review to retrieve the learner's selected option, the correct option, evidence sentence and explanation from the persisted attempt. The quiz option model remains answer-key-free; correctness is only queried for submission and post-attempt review. Added instrumentation coverage for wrong-answer persistence and review reconstruction.
- Replaced the single-item placeholder in the listening library with a list of all installed approved lessons plus the separately labelled demo, including category, level, duration, bookmark state and a direct course entry.
- Fixed a state-coupling issue in the listening home: the daily assignment now has its own ID, separate from the currently selected library lesson. Opening another lesson can no longer silently change the item labelled as today's assignment. The home note also distinguishes demo disclosures from approved-course notices.
- Hardened migration behavior for existing installs: the daily planner now removes legacy demo rows from the formal assignment table before looking up or creating the day's assignment. Added an instrumentation case that seeds a pre-fix demo assignment and verifies it is removed without creating a replacement when no approved lesson exists.
- Made the daily planner's deterministic seed sensitive to each candidate's `updated_at` timestamp as well as its stable ID/audio URI, so catalog revisions can change future tie-breaks without random ordering.
- Hardened answer submission: a choice must belong to the question and the question must belong to the active attempt; duplicate submissions are idempotent and cannot overwrite the first recorded choice. Added instrumentation cases for duplicate and cross-question submissions.
- Made attempt completion derive its score from persisted answers rather than trusting a UI-supplied number; incomplete attempts cannot be marked complete, and repeat completion calls cannot overwrite the original score. Instrumentation coverage now completes every question and verifies the immutable derived score.
- Tightened the question contract to 3-8 MCQ items per imported lesson, matching the daily planner's eligibility gate. The manifest validator no longer accepts dictation/shadowing as MCQs; those need dedicated free-response practice structures rather than fake multiple-choice records. Attempt completion also rejects incomplete answer sets.


## 8. Work log — additional reliability pass

- Upgraded the existing Words database from schema v6 to v7 with an audio_revision field on listening playback checkpoints. Existing playback rows migrate with an empty revision; they retain saved speed but their position is not restored against a different audio revision.
- Playback restore and checkpoints now bind to episode ID, media URI and episode update revision. This prevents a stale position from being applied when an episode's audio/catalog revision changes, while retaining the learner's speed preference.
- Hardened MediaController speed reads/writes and checkpointing to verify both media ID and URI, avoiding cross-episode state reads when media items share a URI.
- Hardened package manifests: audio paths must live under audio/, MIME types must be from a supported allowlist, and packages are limited to 500 episodes. Added negative tests for non-audio paths and unsupported MIME values.
- Important migration safeguard: v5→v7 upgrades first create the listening tables with the latest schema; the v7 migration checks whether audio_revision already exists before attempting ALTER TABLE, preventing a duplicate-column crash on a fresh v5 upgrade.
- Updated the migration and fresh-install instrumentation assertions for schema v7. CI and emulator execution are still pending; these changes are code-complete but not yet verified by a successful run.

- Added a dedicated v6→v7 migration fixture proving existing playback position/speed survive while the new revision column defaults safely; the v5→v7 fixture also guards the create-table-plus-upgrade path against duplicate-column failures.
- Made attempt completion transactional and truly idempotent: after completion it returns the persisted original score without recalculating against a later-mutated question catalog. Added a regression case that inserts a new question after completion and confirms the historical score is unchanged.
- Tightened player identity checks to include episode ID, URI and catalog revision. Media metadata now carries the loaded audio revision; the service refuses to persist a checkpoint if the database catalog revision no longer matches the media item currently loaded.
- Added CI source guards for audio revision checks, v6→v7 migration coverage and immutable completed scores. This does not replace Android runtime tests; the latest commit still needs a successful CI run.
- Final source review caught and fixed a nullable MediaItem checkpoint guard and ensured speed controls also verify the active episode/catalog revision before changing playback state.


## 9. Import implementation handoff — 2026-11-21

- Added [`LISTENING_IMPORT_REVISION_DESIGN.md`](LISTENING_IMPORT_REVISION_DESIGN.md) before implementing the importer. The current mutable episode/question rows are not sufficient to preserve historical attempts during content updates; a DB transaction alone does not solve that semantic-versioning problem.
- Proposed next schema step: v8 immutable episode-revision snapshots, attempt-to-revision pinning, revision-scoped verified audio paths, and a staged-file / atomic-DB-publication protocol.
- Importer UI and WorkManager download orchestration remain blocked on revision snapshot migration, repository reads, and rollback/history tests.
- No APK was built, no branch was merged or rebased, and no production content was added in this pass.


## 10. Revision-safety implementation update — 2026-11-21

Implemented on `feature/listening-mvp` (schema v10; not yet runtime-verified):

- Added immutable `listening_episode_revisions`, `listening_revision_sentences`, `listening_revision_questions`, and `listening_revision_choices` tables.
- Added episode current-revision pointer, attempt-pinned `content_revision_id`, and daily-assignment-pinned `content_revision_id`.
- v7→v8 migration snapshots the existing episode metadata, transcript, questions, choices and answer keys, then binds existing attempts to their legacy revision. v8→v9 pins existing daily assignments. v9→v10 adds immutable episode metadata/audio URI fields and backfills existing revisions.
- Attempt creation now pins the active daily assignment revision when applicable; otherwise it pins the episode's current revision.
- Added attempt-scoped reads for lesson metadata, transcript, questions, options and correct answers. Quiz submission validation, scoring and completed review now query the attempt revision rather than mutable catalog content.
- MainActivity's blind-listening, quiz and review stages now request episode/transcript/question data through the active attempt ID.
- Added regression coverage that mutates mutable episode/question/choice rows after an attempt and asserts the pinned attempt still reads its original metadata, transcript, questions, choices, answer key and score.
- Added guards for partial legacy schemas used by the v6 migration fixture; no missing optional listening table should make the playback-column upgrade fail.

### Remaining before importer can be enabled

- The package importer is still not implemented. The manifest validator, safe ZIP staging and audio integrity helpers remain building blocks only.
- Import must create a new immutable revision and its snapshots, verify and durably stage revision-scoped audio, then atomically publish the revision pointer. It must not update or delete historical snapshots.
- The importer must preserve old mutable question parent rows while answers still have a foreign key to `listening_questions`; if a package removes a question, the catalog needs an explicit active/tombstone policy and planner queries must use the active published revision.
- Audio playback currently snapshots the episode audio URI and metadata for attempt display, but there is no package-import publication journal, no persisted package identity/digest table, and no crash-recovery worker yet.
- Daily assignment version pinning is stored and used by `startAttempt`; verify the device-local date semantics on device and add a direct assignment-to-attempt test.
- Android compilation, migration instrumentation, emulator playback and physical-device playback have not been run in this pass. Do not mark these gates green until a fresh workflow run succeeds.

### Verification record

- Source-level inspection completed for database migration, repository query paths and MainActivity call sites.
- GitHub PR workflow file includes Android emulator migration tests and static safety checks, but no workflow run was returned for the latest code commits. A passing CI result is therefore not established.
- No APK was built; no merge or rebase was performed.


## 11. Final handoff checkpoint — 2026-11-21

Current implementation checkpoint:

- Database schema is now **v10**.
- Revision-owned transcript/question/choice snapshots and episode metadata/audio URI snapshots are present.
- Existing v7 attempts are backfilled to a legacy revision; daily assignments receive a revision pin.
- New attempts pin the daily assignment revision when the same episode is assigned for the device-local current date; otherwise they pin the episode's current revision.
- MainActivity's active blind-listening, quiz and review screens now read lesson metadata, transcript, questions, answer choices and correct-answer feedback by attempt ID.
- Score completion and historical answer review use the attempt's pinned revision.
- Regression coverage includes live catalog mutation after an attempt and a v7-to-v10 legacy snapshot backfill scenario.
- The PR validation workflow now has source guards for revision-pinned reads and the migration regression test.

Validation actually performed in this handoff:
- Re-fetched all touched source/test/workflow/documentation files from the feature branch.
- Passed source-level assertions for schema v10, all four immutable snapshot tables, attempt and daily-assignment pins, attempt-scoped Activity reads, migration tests and CI guards.
- Executed a SQLite in-memory migration/query smoke simulation for the v7-style episode/sentence/question/choice/attempt/answer schema; snapshot backfill, answer review query and `PRAGMA foreign_key_check` succeeded in that simulation.
- **Not run:** Android Gradle compilation, Android instrumentation tests, emulator playback, physical-device playback, or a full GitHub Actions workflow. The latest commit has no workflow run returned, so do not describe CI as passing.

Branch state at this checkpoint:
- `feature/listening-mvp` remains separate from `main`; compare reports 192 commits ahead and 8 behind.
- No merge, rebase or APK build was performed.

Next safe implementation slice:
1. Add a persisted package identity/version/digest registry and implement the importer against the v10 immutable revision tables.
2. Stage audio at unique revision-scoped destinations; verify size/SHA-256 and durable file existence before publishing the current revision pointer.
3. Add removed-question tombstones/active catalog policy; never delete question parents still referenced by `listening_answers`.
4. Add importer idempotency, downgrade/conflict, rollback and process-interruption tests.
5. Run the Android CI workflow, fix all compile/instrumentation failures, then validate Media3 audio and offline playback on devices.
6. Only after those gates pass should course packages, WorkManager sync and release packaging be enabled.


## 12. Package importer implementation checkpoint — schema v11

Implemented on `feature/listening-mvp`:

- Database schema v11 adds a persisted package registry (package ID, monotonic package version, archive SHA-256, content revision, import time) and package-to-episode ownership/active-catalog records.
- Added `ListeningContentPackageImporter`: extracts to a private staging directory, validates the manifest, requires the archive's audio entries to exactly match declared audio paths, verifies every audio file's declared byte count and SHA-256, and stores audio under an immutable revision-specific destination.
- Import rejects package downgrades, same-version/different-digest conflicts, duplicate audio paths, ownership collisions, expired licenses, and attempts to promote an existing demo or legacy lesson into formal package content.
- Source and license identifiers are checked for metadata conflicts instead of silently overwriting provenance.
- A single SQLite transaction creates the package registry update, revision metadata, revision-owned transcript/questions/choices, compatibility parent rows, package ownership mappings, and finally publishes each episode's current revision pointer.
- Parent question and sentence IDs are namespaced per content revision. Old parent rows remain present so existing answer foreign keys remain valid; old assignments and attempts retain their pinned revision and old audio file.
- Package updates deactivate removed package episodes instead of deleting them. The active daily planner now validates and selects from immutable revision snapshots rather than accumulated mutable parent rows.
- Added orphan-audio scanning that only removes importer-owned revision files not referenced by any revision row.
- ZIP extraction now caps the decompressed manifest at 1 MiB. Added an immutable audio installer that refuses to replace an existing revision file.

### Tests added, runtime status pending

- Package import/idempotency and historical attempt/assignment revision retention.
- Newer package revision publication, same-version digest conflict rejection, and downgrade rejection.
- Protection against promoting the system-TTS demo into approved formal content.
- Immutable audio destinations cannot be overwritten.
- Fresh-install and v5/v6/v7 migration expectations updated to schema v11.

The new Android instrumentation tests have been authored but **have not yet run**. Android compilation, emulator instrumentation, playback verification, physical-device checks, and GitHub Actions status remain pending. No APK was built, and PR #1 remains a draft without merge or rebase.


## 13. Interrupted-session recovery and stage-motion checkpoint

Additional implementation on `feature/listening-mvp`:

- Added repository accessors for attempt stage, completion state, selected answer, and persisted correct-answer count.
- MainActivity stores only the active attempt pointer and an unsubmitted selection in preferences; submitted answers remain authoritative in SQLite.
- On Activity/process recreation, the app restores the attempt's pinned revision, finds the first unanswered question, restores a still-valid unsubmitted choice, and completes the attempt if all answers were already saved before interruption.
- The home screen exposes a resume action for the active unfinished lesson. If the preferences pointer is missing, the latest in-progress attempt is recovered from SQLite. Starting a different new lesson marks the replaced unfinished attempt as abandoned; returning from a completed review clears the active pointer without deleting attempt history.
- Quiz evidence and review scores now use attempt-pinned content and persisted answers, not mutable catalog rows or transient Activity counters.
- Listening stage changes use a restrained two-phase transition: 115 ms exit and 220 ms entrance, cubic easing, a small vertical offset, and hardware-layer rendering. The bottom navigation remains stationary; ordinary option selection rerenders stay immediate to avoid unnecessary motion.

These changes have source guards and instrumentation assertions, but the Android tests remain unexecuted. Animation timing and gesture feel still require emulator and physical-device review; do not label the motion system final until that review is complete.


## 14. Playback revision continuity

A further edge case was closed: background playback checkpoints no longer compare only against the episode's latest mutable catalog row. The playback service now validates the media item's revision token against any matching immutable revision row, then persists the checkpoint under that token. This allows an already-playing older revision to checkpoint safely after a newer package revision is published, without attributing its position to the new audio.

A regression assertion covers recognition of both the old and new revision audio and rejection of an unknown URI. This remains source/test coverage pending Android execution.


## 15. Import source snapshot and concurrent recovery guard

- The importer now copies the selected ZIP to a private staging snapshot while computing its SHA-256, then extracts and imports that exact snapshot. This closes the source-file time-of-check/time-of-use gap.
- Compressed archive size is capped at 1.1 GB in addition to entry-count, total decompressed-size, and manifest-size limits.
- Package imports and orphan recovery share a process-wide lock, preventing recovery from deleting an audio file between its staging move and database publication.
- Staging cleanup also covers archive-snapshot failures.

Static source assertions and an independent SQLite schema/foreign-key simulation pass. Android Gradle compilation and instrumentation execution remain pending.


## 16. Daily-assignment display and scroll continuity

- Daily home and intro screens now resolve lesson metadata through the daily assignment's pinned revision, matching the revision that `startAttempt` will use even if the package catalog was updated after the assignment was created.
- Ordinary quiz answer rerenders preserve the listening scroll offset; actual stage transitions intentionally reset to the top.


## 17. Low-storage protection

### Follow-up hardening — staging and extraction headroom
### Follow-up hardening — exact installed snapshot verification

- Same-version/same-digest imports no longer trust the package registry, row counts and audio file alone.
- The idempotency check now compares the installed immutable revision metadata, sentence IDs/timestamps/text/translation/notes, question IDs/type/prompt/explanation/evidence links, and every choice's order/content/correctness against the validated package manifest.
- It also verifies the revision hash, active package ownership, current-revision pointer, approved/non-demo status, audio path confinement and audio checksum.
- Added a regression test that tampers with an installed question row, confirms the repeated import is rejected, restores the row, and confirms the intact package is idempotent.
- CI source guards now check these exact-snapshot comparisons. Runtime verification remains pending.

- Before copying a ZIP into private staging, the importer now checks that the staging filesystem has room for the compressed snapshot plus the 64 MiB reserve.
- ZIP extraction checks remaining filesystem space before each write and stops while preserving the configured reserve. A failed extraction removes its partial staging directory.
- The importer passes the same reserve through the extraction path, then separately checks durable content storage for the complete declared audio payload plus reserve before publishing audio.
- Added an Android instrumentation regression test that forces an impossible reserve and verifies partial extraction is cleaned up. The PR source guard now checks these protections.
- This is source/test coverage only until the Android emulator workflow actually runs; no APK build was started.

Before copying revision audio into the durable content directory, the importer now checks available storage against the package's total declared audio size plus a 64 MiB reserve. This avoids partially filling a device during an otherwise valid package install.
