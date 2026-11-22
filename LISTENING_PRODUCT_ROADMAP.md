# Words Listening — Product & Delivery Roadmap

> **历史文档说明（2026-11-22）：** 本文件累计记录 34 个阶段的设计和实现快照。各节复选框反映写入当日状态，可能已被后续实现或决策覆盖，不能直接作为当前总待办。当前真实进度、历史冲突裁决和唯一执行顺序，统一见 [PROJECT_AUDIT_AND_MASTER_ROADMAP.md](PROJECT_AUDIT_AND_MASTER_ROADMAP.md)。第 22 节“严格不重复”覆盖第 18 节任何耗尽后重复旧课的设计。APK 生产暂停，版本固定 1.8.5 / 26。

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
### Offline rights and download architecture decision

- Package import now requires both `in_app_playback` and `offline_download`. The formal daily planner independently enforces both rights, preventing a streaming-only lesson from entering the offline course workflow.
- The next network layer should use Android's system `DownloadManager` for large archive transfer instead of hand-writing HTTP Range/resume logic. It already handles long-running HTTP downloads, retry across connectivity changes and reboot, and exposes durable status/progress.
- Use WorkManager for the durable post-download pipeline (archive hash/size verification, package import, cleanup, retryable catalog refresh), not as the transport for a 1.1 GB transfer. Android 16 can impose job-quota pressure on long-running foreground workers; the OS download service avoids duplicating that transfer scheduler.
- Persist the DownloadManager request ID plus package ID/version, expected archive size and SHA-256 in a new download table. A completion receiver should enqueue unique install work; app-start reconciliation must recover missed broadcasts. The worker must re-check the system download status, exact size and digest before invoking the existing importer.
- Before exposing remote package downloads, add a signed catalog index (Ed25519 signature verified against an app-pinned public key) binding package ID/version, HTTPS URL, archive size and SHA-256. Rights fields inside a ZIP are publisher-supplied metadata, not cryptographic proof by themselves. Until signature verification and a trusted catalog endpoint exist, package import stays a development/internal capability.
- This is the selected implementation direction, not yet shipped code. No download endpoint or production audio library has been configured.

### CI validation efficiency and emulator runner repair

- Inspected the latest available failed PR validation run. Static checks completed successfully; the Android emulator never booted, so instrumentation did not start. The runner lacked accessible `/dev/kvm`; the action launched the newer emulator with `-accel off`, which that emulator version rejects unless TCG is explicitly enabled. This was an infrastructure startup failure, not a Kotlin compile error or failed test assertion.
- Added the upstream-recommended KVM udev permission setup and a `GITHUB_ENV` TCG fallback when KVM is unavailable.
- Added explicit `compileDebugKotlin` and `compileDebugAndroidTestKotlin` tasks before emulator startup. This gives a compile result without assembling or uploading an APK, even if emulator startup later fails.
- The latest available workflow run still targets an earlier branch commit. The new workflow changes and latest source must be validated by a fresh run before marking either compile or instrumentation green.

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


## 18. Continuous listening catalog and daily rotation

The daily planner now uses lifetime assignment history instead of looking back only seven days:

- Every eligible episode is selected once before any episode receives its next daily assignment.
- A newly installed episode enters the current pass with zero assignments and is therefore selected before already-served lessons.
- When all eligible episodes have been assigned, the next pass starts with the episode whose previous assignment is oldest.
- Category, accent, and level balancing remain deterministic tie-breakers; the existing L3/L4 preference is retained without allowing advanced lessons to starve unassigned lessons.
- Assignment history is aggregated by episode in SQLite (count + last assigned date), so the planner does not load every historical assignment row into memory.
- Added an instrumentation regression test covering a two-lesson pass, a newly arriving third lesson, and the first repeat after the full pass.

This closes the short-window repeat problem in local daily assignment. It does **not** yet create new course content or download updates. Continuous content supply still requires the signed remote catalog, periodic catalog refresh, DownloadManager transfer, WorkManager installation/reconciliation, and a sufficiently large licensed audio library. Until those are connected, the app can rotate only the approved lessons already installed on the device. Runtime execution of the new instrumentation test remains pending.


## 19. Open-source content and distribution research

Reviewed current public implementations and upstream documentation to avoid rebuilding the content-delivery wheel:

- **DictaLearn** (MIT app code): useful reference for a portable lesson package containing audio plus sentence timing/transcript data, lesson import/export, and downloading additional books separately from the small base app. Its current built-in library uses neural-voice audio, so it is an architecture reference only and is not a source for our required human-recorded production lessons.
- **SpeakAlong** (MIT app code): useful reference for a static catalog JSON plus per-book timed sentence data and separately hosted audio assets. Its content is separately governed from its code; do not copy its recordings/translations into Words without verifying the exact content terms.
- **Voxshelf** (GPL-3.0 app): useful reference for a generated catalog, nightly catalog refresh, discovery filters, and a local download shelf. Do not copy its GPL implementation into this app without a license compatibility review.
- **LibriSpeech (OpenSLR SLR12)**: the strongest current candidate for a starter human-read audio corpus. OpenSLR describes about 1,000 hours of read English speech derived from LibriVox audiobooks, carefully segmented/aligned, and lists CC BY 4.0. Its audiobook narration is suitable for narrative listening; level assignment, Chinese translation, comprehension questions, and lesson curation still need editorial work. Use only selected, quality-checked clips rather than bundling the full multi-gigabyte corpus.
- **Clarity Speech Corpus**: contains roughly 10,000 individually recorded British-English sentences from 40 speakers, with prompt metadata and a CC BY 4.0 research article. However, the corpus article's additional terms exclude public broadcasting of the voice recordings except as part of a research project. Treat it as a research/benchmark candidate, not a production app audio source, unless the underlying dataset's exact license and distribution rights are independently cleared.
- **LibriVox**: a potential human-narrated source for public-domain literature and short-work collections. Its API supports incremental discovery with the since parameter, pagination with offset, and a maximum limit of 500; the project asks clients to space large requests by several seconds. It explicitly warns that its recordings are public domain in the USA but may not be public domain in every other country. Therefore each candidate work needs territorial review and metadata before inclusion; a catalog entry alone is not blanket global clearance.

### Chosen long-term model

1. Keep the app's learner-facing library local-first and offline-capable.
2. Publish a versioned static catalog index and immutable ZIP course packages separately from the APK. The catalog lists package ID/version, HTTPS URL, archive size, SHA-256, content metadata, and signing key ID.
3. Have a scheduled publisher job refresh source metadata politely, process only new/changed candidates, and build a human-reviewed editorial queue. It must never automatically turn an arbitrary public-domain audiobook into a graded lesson without transcript, timing, question, level, and audio-quality review.
4. Publish only approved packages through a signed catalog. The app periodically checks the index, verifies its signature and package digest, downloads with Android DownloadManager, and installs through the existing revision-safe importer using unique WorkManager jobs.
5. Keep a rolling local cache with a configurable storage budget while preserving pinned revisions for attempts and daily assignments. A package may be evicted only when it is not in use, not pinned by an unfinished attempt, and not needed for a saved playback position; its catalog metadata and learner history remain.
6. Maintain a fallback: when offline or when the feed is unavailable, continue the current local rotation. When the installed catalog is exhausted, repeat from the oldest-assigned lesson rather than showing an empty daily screen.

This architecture removes the need for the learner to ask for manual course updates. It does not remove the need for a reliable publisher and ongoing content review. Human-recorded source material, accurate transcripts, question quality, and legal clearance remain editorial gates; automated discovery and delivery handle the repetitive distribution work.

Research links:
- DictaLearn: https://github.com/BurakYildizGameDev/dictalearn
- SpeakAlong: https://github.com/r4yg/speakalong
- Voxshelf: https://github.com/ezlabs/voxshelf
- OpenSLR LibriSpeech SLR12: https://openslr.org/12/
- Clarity Speech Corpus paper and data rights: https://pmc.ncbi.nlm.nih.gov/articles/PMC8881678/
- LibriVox API: https://librivox.org/api/info
- LibriVox API update and polite polling guidance: https://librivox.org/2026/09/16/librivox-api-update/
- LibriVox public-domain / territorial notice: https://librivox.org/pages/public-domain/


## 20. New-content-first supply — product correction and long-term operating model

**Important correction:** a lifetime rotation algorithm is not a content-supply system. Reassigning an old episode is review, never a new lesson. The previous local rotation remains useful only as an offline/review fallback; it must not be presented as a way to create fresh daily content.

### Learner-facing rules

1. Every episode has an immutable identity and a learner state: unseen, started, completed, or review-due.
2. The daily "new lesson" slot selects only approved episodes the learner has never started. A completed or previously started episode must never be relabelled as new.
3. New approved episodes are inserted into the unseen queue immediately, ahead of any repeat. Do not wait for the current catalog pass to finish.
4. Repeats belong in a separately labelled review queue, selected by learning need and spaced-repetition timing. They do not consume the new-lesson slot.
5. If there is no unseen approved episode available, say so honestly and offer a review lesson; do not silently recycle an old episode as today's new lesson.
6. A package revision with changed audio/transcript/questions is not automatically a new lesson. It is a new revision of the same lesson unless editorial review confirms that it is materially distinct and assigns a new episode identity.
7. Deduplicate across providers using provider/source IDs, normalized transcript fingerprints, and audio fingerprints/segment boundaries. Re-uploading, re-encoding, or slightly re-slicing the same recording must not inflate the new-lesson count.

### Continuous publisher pipeline

Run a separate content-publisher service on a schedule (daily metadata discovery; batch processing and publication several times per week). Do not put this workload inside the Android app.

1. **Discover:** poll source catalogs incrementally (for example, LibriVox `since` cursor); check versioned dataset releases (for example, Common Voice Spontaneous Speech); persist source cursor, provider ID, discovery time and source metadata.
2. **Rights gate:** record the exact source license/version, attribution, allowed redistribution/offline actions, territory and any restrictions before downloading or processing audio. Unknown, non-commercial-only, research-only, or unclear redistribution rights go to quarantine, never production.
3. **Acquire and normalize:** download only eligible source assets; keep original provenance; normalize audio into app-supported formats without changing the source identity; create stable segments with explicit source offsets.
4. **Transcribe and align:** use supplied human-checked transcripts when available; otherwise generate a draft transcript and word/sentence timing, then run audio-to-text alignment. Automated output is provisional, not proof of correctness.
5. **Create the lesson:** prepare reviewed English transcript, Chinese translation, level/topic/accent metadata, learning objectives, evidence-linked comprehension questions, distractors, explanations, and 5–10 vocabulary items. Keep the original recording human-recorded; TTS may assist internal testing only.
6. **Quality gates:** validate audio decodability/duration/loudness, transcript coverage and alignment, sentence timing, translation completeness, answer-key evidence, question uniqueness, level suitability, duplicate fingerprints, attribution and offline rights. Any failure blocks publication.
7. **Editorial approval:** maintain a review queue and audit trail. Human approval is required for the initial production pipeline; later, only narrowly defined low-risk transformations may be auto-approved after measured error rates and rollback controls justify it.
8. **Package and publish:** produce immutable versioned ZIP packages, calculate exact byte size and SHA-256, publish a signed catalog index, and retain prior catalog versions for rollback. Never replace bytes behind an existing package version.
9. **Observe and replenish:** track candidate → rights-cleared → processed → QA-passed → approved → published counts, failures, source freshness, and unseen-library runway. Alert when the unseen backlog falls below its configured floor.

### Supply targets and operational reality

- Initial launch target remains 30 days of daily new lessons plus 60 separate review/extra episodes, subject to rights and QA.
- Long-term target: publish at least 7 approved new lessons per week to sustain one new daily lesson; aim for 10–20 per week to build a buffer and support varied levels/topics.
- Maintain a rolling minimum of 30 days of unseen approved content. Pause or reduce low-quality publication rather than lowering rights or educational standards.
- A source API or public dataset does not guarantee a fixed number of suitable new lessons every day. The service must monitor source yield and maintain a prepared backlog. If supply drops, the app must be transparent instead of pretending old material is new.
- GitHub Actions can validate manifests, run tests and publish a static catalog as an initial low-cost deployment. It should not be the only durable scheduler or state store: source cursors, editorial review state, retries, monitoring and backup need persistent storage and operational ownership. The publisher should be deployable independently from the Android app and APK.
- The app checks for catalog updates periodically when network constraints allow, but learning never depends on the publisher being online. Downloaded approved content remains available offline.

### Source strategy

- **Common Voice Spontaneous Speech:** primary candidate for natural conversational clips; the upstream project describes open-ended unscripted recordings and CC0 audio. Pin each dataset release and retain contributor/source metadata; it still needs educational curation.
- **LibriVox:** incremental discovery source for human-read public-domain literature. Use the API's `since` cursor and polite pagination. Do not infer worldwide rights from its US public-domain status; each work needs territory-specific review.
- **LibriSpeech / OpenSLR SLR12:** starter reservoir for read-speech listening and sentence-level practice, not a substitute for spontaneous conversation.
- **The People's Speech and other large corpora:** candidate discovery only until exact per-subset audio and redistribution terms are verified. Repository/code license and dataset/audio license must be treated separately.
- **RSS, podcasts, TED/video platforms and ordinary web audio:** metadata discovery does not grant audio download, offline redistribution or derivative-course rights. Exclude from production unless the specific recording has explicit compatible rights or a direct license.

### Architecture boundaries

- Publisher owns source connectors, rights ledger, editorial queue, media processing, lesson QA, package generation and signing.
- Static catalog/object storage owns signed manifests and immutable packages; no user-specific progress belongs there.
- Android owns local catalog cache, signature verification, downloads, package installation and learner progress. Use the existing revision-safe importer; large transfers use DownloadManager and durable post-download installation uses WorkManager.
- The app must verify catalog signature, package ID/version, HTTPS location, exact size and SHA-256 before import. A manifest's self-declared license is not cryptographic proof of rights.
- Keep new-lesson selection separate from review selection in repository APIs and UI models. Add regression tests proving that a completed episode cannot re-enter the new queue and that a newly published episode is selected before any repeat.

### Implementation order

1. Add explicit unseen/new versus review-due states and tests to the local repository/planner.
2. Define source provenance, rights ledger and publisher candidate schemas.
3. Implement Common Voice release discovery and LibriVox incremental discovery as isolated publisher connectors; do not download unrestricted corpora wholesale.
4. Build a durable editorial queue and processing/QA pipeline; start with a small controlled batch.
5. Generate immutable signed catalogs and course packages; add catalog signature verification on Android.
6. Implement periodic catalog refresh, DownloadManager transfer, WorkManager installation/reconciliation and storage policy.
7. Add monitoring for source yield, publishing cadence, unseen backlog runway and failed jobs.
8. Only then enable unattended scheduled publishing. Keep APK packaging paused until the rest of the product is ready.

This section supersedes any interpretation of the earlier "whole-catalog rotation" as a way to provide fresh daily content. Rotation is a fallback for review/offline continuity; continuous new lessons require the independent publisher pipeline above.


## 21. Continuous content supply — consolidated decision and current audit (2026-11-21)

This section supersedes earlier roadmap statements where implementation status has since changed. The detailed publisher, source-adapter, rights, deduplication, signed-catalog, Android-sync, and acceptance-test contract is maintained in `LISTENING_CONTINUOUS_CONTENT_SUPPLY_SPEC.md`.

### Product decisions locked

- New lessons and review work are separate queues. A started episode is never eligible as a new lesson again, even if its package revision changes.
- An updated revision keeps the same stable episode identity and does not reset first-start state, attempts, bookmarks, or playback history.
- New published episodes enter the unseen pool immediately; they must be offered before an already-assigned episode is repeated.
- If no unseen published episode exists, the app must say so and offer review work. It must never disguise an old lesson as a new one.
- Raw discovery is not publication. Rights, audio, transcript, translation, question evidence, and educational quality must pass review before signed catalog publication.
- The publisher is independent from the APK. A new course must be installable without rebuilding the app.
- Local verified courses remain usable offline if the publisher, network, or download fails.

### Source strategy

1. LibriVox incremental metadata discovery is the first provider adapter candidate. Every recording/work still requires item-level rights and territorial review.
2. Common Voice is the natural-speech batch source candidate. Pin exact dataset releases and licenses; do not promise a daily source cadence.
3. LibriSpeech is a useful finite read-speech seed/backfill, not the only or primary continuously refreshed conversational source.
4. Add other sources only after the audio, transcript, metadata, and redistribution rights are independently verified.

### Implementation status — do not overstate

- [x] Complete-catalog assignment history aggregation and new-episode-before-repeat selection are implemented in the Android repository/planner.
- [x] Regression instrumentation tests cover a new episode arriving during a catalog pass and verify that a repeat waits until the eligible pass is exhausted.
- [x] A content-supply engineering specification now defines source adapters, candidate states, deduplication, QA, editorial review, signed publication, app sync, reserve monitoring, failure recovery, and acceptance tests.
- [x] The planner's unresolved `recentHistory` reference was found during source audit and fixed; topic/accent/level tie-break counts now all derive from the supplied complete assignment history.
- [ ] Kotlin compilation and Android instrumentation must pass on the current head before this rotation slice is considered runtime-verified.
- [ ] A persistent candidate inbox/provider cursor store and the first source adapter are not implemented yet.
- [ ] Audio/transcript processing and editorial review workflow are not implemented yet.
- [ ] Production catalog signing/publishing and Android remote catalog/download orchestration are not implemented yet.
- [ ] No production human-recorded course reserve is claimed by this roadmap.

### Validation snapshot

- Current planner/repository/test/workflow static audit: 12/12 targeted assertions passed after the planner fix.
- The rotation instrumentation test exists and covers the intended sequence (two existing lessons, a newly inserted third lesson, then the oldest lesson repeating).
- The test's epoch timestamps were checked against the intended dates: 2026-11-21 through 2026-11-24 UTC.
- This static audit is not a Kotlin compile and is not an Android emulator run.
- The current commit has no associated PR workflow run/check result yet. Earlier emulator CI failed during emulator startup; it is not a code-test pass.
- PR validation workflow runs Kotlin compile tasks and Android instrumentation tests, and does not package or upload an APK.
- The APK workflow remains manual-dispatch-only; no APK was built in this work session.

### Immediate next execution order

1. Re-run PR compile/instrumentation validation for the latest code; diagnose workflow trigger/infrastructure if no run appears.
2. Add a first-start/new-lesson eligibility invariant and tests that cover completion, abandonment, revision update, and newly published course insertion. The current daily-assignment count is not by itself a substitute for the user's actual first-start state.
3. Design and migrate the candidate/provenance/cursor persistence model only after the source adapter fields and withdrawal policy are fixed.
4. Implement LibriVox incremental discovery as a dry-run importer first; persist candidates and rights evidence, but do not publish audio automatically.
5. Implement Common Voice versioned batch import after pinning the exact dataset release and item-level terms.
6. Add editorial processing/review and only then implement signed catalog publication.
7. Integrate Android download/install orchestration with the existing revision-safe package importer.
8. Run failure-injection, migration, offline, and 365-day catalog-growth tests before declaring the system durable.

### PR and build policy

PR #1 remains Draft and unmerged. Keep the feature branch isolated. Do not build a distributable APK during this implementation phase. Compile-only Kotlin tasks and test APK installation inside CI are validation steps, not a user-facing release APK; report them separately. A final distributable APK is deferred until all feature work, audit, and release checks are complete.


## 22. Strict no-repeat correction — supersedes rotation behavior (2026-11-21)

The earlier "complete catalog pass, then repeat the oldest lesson" rule has been rejected because it still repeats old content. The final product rule is stricter:

- A stable episode ID may be assigned to the daily new-lesson queue at most once.
- Existing episode revisions, retitled courses, changed translations, changed questions, or re-encoded audio do not reset new-lesson eligibility.
- All prior assignment dates are considered; backdated device dates cannot make an old episode appear new.
- Newly published episode IDs immediately enter the unseen pool.
- When the unseen pool is empty, the planner returns `null`. The app must show an honest no-new-lesson state and offer the separate review queue; it must not silently recycle old content.
- A skipped assignment remains accessible as a pending/previous lesson, but is not assigned again as a new lesson.

### Current implementation changes

- [x] Fixed the unresolved planner variable found in source audit.
- [x] Changed planner selection to filter out every episode ID already present in assignment history.
- [x] Changed history aggregation to include assignments on all dates except the date currently being resolved.
- [x] Added regression coverage for initial one-time assignments, backdated requests, new-course replenishment, empty unseen pool, and same episode ID with changed revision metadata.
- [x] Updated PR CI source guards to enforce the strict no-repeat behavior and updated regression-test names.
- [x] Updated the continuous content supply specification to make stable episode identity and one-time assignment explicit.

### Remaining work / validation gates

- [ ] Recheck the app UI when planner returns `null`; demo preview must remain clearly labelled as demo and must not be presented as a new lesson.
- [ ] Preserve access to an assigned-but-not-started lesson so skipping a day does not lose it.
- [ ] Run Kotlin compilation and Android instrumentation against the latest head; no current-head CI pass is claimed.
- [ ] Implement the independent review queue.
- [ ] Implement candidate ingestion, source cursors, rights quarantine, editorial review, signed publisher, and Android remote sync.

This section supersedes the older full-catalog repeat policy and any earlier roadmap wording that implies an episode is eventually recycled as a daily new lesson.


## 23. Long-run no-repeat regression coverage (2026-11-21)

Added an instrumentation regression simulation that introduces seven distinct lesson IDs every week, assigns one unseen lesson per day for 371 days, asserts every assigned ID is unique, and verifies the planner returns `null` once all 371 lessons have been assigned. The PR CI source guard now requires this test to remain present.

This test is authored but has not yet run on an Android emulator. It must pass in CI before the strict no-repeat implementation is considered runtime-verified.


## 24. Latest targeted validation after history-query cleanup (2026-11-21)

- The standalone Kotlin compilation of the current planner implementation passed against a minimal `ListeningEpisode` model stub. This validates the planner source's Kotlin syntax/type use in isolation, not the full Android app.
- The 371-day weekly-growth simulation passed: 371 unique episode IDs were assigned once, the exhausted catalog returned `null`, and a title/audio/revision metadata change did not make an assigned episode new again.
- The exact assignment-history SQL was executed against an in-memory SQLite fixture. It returned per-episode assignment counts and latest immutable revision metadata, and it included assignments on all dates other than the currently resolved date.
- A repository-wide scan of the 16 Kotlin files under `android/app/src` found no remaining `lastAssignedDate` references after simplifying the history model and cursor mapping.
- PR CI guards were updated to reject stale history fields and require the long-run regression test.
- Full Android Gradle compilation, instrumentation tests, and physical-device tests remain unverified because there is no Actions run for the current head. Do not treat the isolated planner compile or SQLite fixture as a full app build.


## 25. Honest exhausted-pool UI state (2026-11-21)

When no unseen formal lesson is available, the daily card now displays `NO NEW COURSE / 暂无正式新课` rather than implying that the fallback demo is today's new lesson. The original short TTS preview remains separately labelled as an optional experience lesson and its explanatory copy says it is not part of the formal course library. The PR CI source guard now checks these labels.


## 26. First operational content discovery adapter (2026-11-21)

- [x] Add incremental LibriVox metadata adapter with documented API parameters, 500-record pagination ceiling, three-second inter-page delay, three-day overlap, stable-ID deduplication and persistent cursor.
- [x] Preserve rights and editorial decisions across repeated discovery; quarantine non-English metadata and default every rights permission to false.
- [x] Add failure-safe cursor advancement and atomic inbox/state writes.
- [x] Add unit tests for response normalization, missing metadata, rights quarantine, non-English quarantine, idempotency and failed-page cursor safety.
- [x] Add PR CI execution of content-pipeline unit tests.
- [x] Add daily scheduled workflow writing to a separate `content-inbox` branch.
- [ ] Execute the Python unit tests on GitHub Actions for the latest feature head.
- [ ] Activate the scheduled worker after merge to the default branch and verify its first real API run.
- [ ] Add editorial UI, audio/transcript processing, rights evidence review, course packaging, signed catalog publishing and Android sync. These are not implemented by metadata discovery.


## 27. Full-catalog bootstrap and resumable paging (2026-11-21)

- [x] Changed first-run behavior from a recent-window sample to a full LibriVox catalog backfill.
- [x] Uses compact API metadata for the 500-item pages; extended per-section metadata is deferred until individual candidates are selected.
- [x] Persists a bootstrap offset and atomically checkpoints the inbox every five pages.
- [x] Resumes failed full-catalog scans from the last checkpoint; repeated pages are safe because candidate identity is provider ID.
- [x] After bootstrap, switches to incremental sync with a three-day UTC overlap.
- [x] Added tests for bootstrap resume, no-`since` initial requests, pagination, deduplication, pacing and incremental overlap.
- [ ] Execute all Python tests on GitHub Actions and verify the first live full-catalog run after merge.


## 28. Independent listening review queue (2026-11-21)

- [x] Added a pure Kotlin score-based review planner with transparent 1/3/7/14-day intervals.
- [x] Selects only valid completed-attempt scores and ignores invalid/empty history.
- [x] Repository query uses the latest completed attempt per episode and excludes demo, inactive, expired, or streaming-only content.
- [x] Review recommendations do not create or mutate daily new-lesson assignments.
- [x] Added a separate "SPACED REVIEW / 薄弱课程复习" card on the listening home screen.
- [x] Added instrumentation regression coverage for each score interval.
- [ ] Verify repository SQL and UI flow in Android instrumentation on the latest head.
- [ ] Extend from whole-course review to question-level and evidence-sentence-level targeting after event tracking is validated.


## 29. Consolidated implementation audit (2026-11-21)

- [x] Added and source-audited the strict unseen-only new-lesson planner, long-run 371-day regression test, and honest empty-pool UI.
- [x] Added an independent whole-course review queue with score-based 1/3/7/14-day intervals, rights/approval filtering, home-screen entry, and protection against interrupting an unfinished attempt.
- [x] Added a resumable full LibriVox catalog metadata bootstrap, five-page checkpoints, incremental three-day overlap, rate-limit retries, stable-ID deduplication, rights quarantine, and a daily scheduled content-inbox workflow.
- [x] Added Python regression coverage for API normalization, rights/language quarantine, pagination, pacing, retry handling, idempotency, cursor safety, and bootstrap recovery.
- [x] Added PR CI steps for Python unit tests, Kotlin compile-only tasks, and Android instrumentation tests without producing a distributable APK.
- [x] Latest targeted source/configuration audit: 32/32 assertions passed.
- [ ] No Actions run is associated with the current head yet. Python tests, full Android Gradle compilation, and emulator instrumentation are therefore not yet runtime-verified.
- [ ] The scheduled discovery worker will begin running only after this workflow is present on the repository's default branch. The current Draft PR has intentionally not been merged.
- [ ] No approved production human-recorded course packages exist yet; metadata discovery is not course creation. Rights review, transcript/translation/question production, editorial QA, signed catalog publishing, and Android background sync remain production gates.


## 30. Source suitability and distribution audit (2026-11-21)

- [x] Added `LISTENING_SOURCE_EVALUATION.md` to distinguish source discovery, internal evaluation, in-app playback, offline download, redistribution, and educational packaging rights.
- [x] Recorded the LibriVox API's 500-record cap, offset pagination, incremental `since` support, and several-second pacing requirement.
- [x] Recorded that Mozilla Common Voice datasets are listed as CC0 but current legal terms make datasets available exclusively through Mozilla Data Collective and ask users not to mirror or redistribute them elsewhere; do not bundle its audio into app courses under those terms.
- [x] Clarified that short ASR speech clips are not complete 5–10 minute learning courses and cannot bypass transcript, translation, question, evidence, rights, and editorial gates.
- [ ] Evaluate only coherent long-form sources with explicit redistribution rights for intended territories, or commissioned recordings with documented contributor releases.


## 31. Android self-updating listening library — implementation slice (2026-11-21)

- [x] Added WorkManager unique immediate + 24-hour periodic signed-catalog refresh scheduling, gated off unless endpoint and public key are configured.
- [x] Added Ed25519 envelope verification, catalog schema/version checks, rollback rejection, HTTPS same-host package URL validation, package size/digest checks, and app-version compatibility checks.
- [x] Added DownloadManager-based Wi-Fi-only package transfer, completion receiver, durable preference records, startup reconciliation, and unique install work.
- [x] Added archive checksum verification, safe extraction preflight, manifest/catalog identity matching, and integration with the existing revision-safe package importer.
- [x] Added an Ed25519 catalog signing tool that reads the private key only from a protected runtime file, plus Python regression tests.
- [x] Added CI guards for the Android sync trust boundary and Python signing tests.
- [x] Added API 35 instrumentation tests for Ed25519 signature verification/tamper rejection, same-host HTTPS enforcement, duplicate package rejection, and minimum app-version compatibility.
- [ ] Configure production catalog endpoint and pinned public key; keep matching private key only in publisher secrets.
- [ ] Build and run Kotlin compilation plus API 35 emulator instrumentation on the latest PR head; fix all failures.
- [ ] Add injected failure tests for DownloadManager completion loss, interrupted installs, digest mismatch, and storage exhaustion.
- [ ] Publish at least one approved human-recorded course package and verify end-to-end installation, playback, offline behavior, and no-repeat assignment on a physical device.
- [ ] Deploy the signed catalog publisher and source/editorial pipeline. APK-free course updates remain disabled until these activation gates pass.


## 32. Static catalog publisher deployment path (2026-11-21)

- [x] Added a manual GitHub Actions publisher that checks out the approved `content-inbox` branch, signs only the reviewed unsigned catalog, and publishes the signed envelope back to the same branch.
- [x] The publisher validates each package ZIP's presence, exact size, and SHA-256 before signing; it refuses paths outside the content-inbox asset root.
- [x] Set the Android default catalog URL to the repository's raw content endpoint; remote sync remains disabled until the matching public key is pinned.
- [ ] Add the Ed25519 PEM private key as the protected Actions secret `LISTENING_CATALOG_PRIVATE_KEY_PEM`.
- [ ] Add the matching Base64 X.509 public key as Gradle property `LISTENING_CATALOG_PUBLIC_KEY` for the release build.
- [ ] Prepare and approve the first production course package and unsigned catalog; do not publish candidate metadata as a course.
- [ ] Run the publisher workflow and verify the signed catalog and package download/install end to end.


## 33. Repository activation state (2026-11-21)

- [x] Created the dedicated `content-inbox` branch from `main` for persistent discovery state and static course/catalog hosting.
- [x] Configured the Android build's default catalog URL to the `content-inbox/catalog/catalog.json` raw-content endpoint.
- [x] Kept the public-key setting empty by default, so no untrusted or unsigned catalog is fetched before the production trust key is pinned.
- [ ] Add the matching publisher private key as an Actions secret and pin the public key in the release build.
- [ ] Populate the content branch with reviewed package ZIPs and a matching unsigned catalog; then run the publisher workflow.
- [ ] Confirm Actions CI and API 35 emulator tests on the latest PR head. The current PR is still Draft and no run/status is visible yet.


## 34. Mainline drift review (2026-11-21)

Compared the current feature head against `main` before any integration attempt:

- The feature branch is 8 commits behind `main`; the mainline-only file diff is limited to `.github/workflows/android-apk.yml` and `android/app/build.gradle.kts`.
- Main re-enabled push/PR APK build and artifact-upload triggers. The feature branch intentionally keeps APK packaging manual-only while implementation is in progress. Preserve that pause; do not blindly copy the main workflow triggers.
- Main still has compileSdk 35 / versionCode 21 / versionName 1.8.0. The feature branch requires compileSdk 36 for the pinned Media3 dependency and carries versionCode 22 / versionName 1.8.1. Preserve the feature toolchain/version changes during eventual reconciliation.
- No merge or rebase was performed. Reconcile these two files explicitly only after CI and instrumentation pass; keep the stable main branch untouched meanwhile.
