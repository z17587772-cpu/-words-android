# Listening Import — Revision-Safe Transaction Design

Status: implementation decision proposal for the next schema change
Target: `feature/listening-mvp`
Scope: transactional package import and preservation of learning history

## 1. Non-negotiable invariants

1. A package is fully extracted, parsed, schema-validated, rights-checked, and audio-verified before it can become visible in the catalog.
2. A failed import must leave the previously installed catalog and audio playable.
3. A stable episode ID identifies the learning topic, not an immutable body of lesson content.
4. Every attempt is permanently bound to the exact lesson revision presented when that attempt began.
5. Completed scores, selected answers, correct answers, explanations, evidence sentences, and first-attempt results must remain reproducible after later package updates.
6. An assignment that has an attempt must not silently move to a different revision or episode.
7. Never use SQLite `REPLACE` on episode/question/sentence parent rows. Never cascade-delete learner records as an update strategy.
8. The demo TTS episode is never imported, approved, or included in the formal daily planner.

## 2. Why the current schema cannot safely accept updates

The current tables model one mutable set of sentences and questions per episode. Attempts and answers refer to those mutable rows. Replacing question text, answer choices, evidence, or explanations in place would make an old attempt appear to have been taken against new content. Deleting old question rows is worse: existing foreign-key cascades can remove answers.

Therefore the importer must not be implemented as a sequence of `INSERT OR REPLACE` statements. A transaction alone is insufficient: it guarantees atomicity, but it does not preserve the semantic version an attempt used.

## 3. Proposed schema v8 direction

Introduce an immutable revision identity and bind each attempt to it.

- `listening_episode_revisions`: immutable revision header keyed by episode ID + revision ID; records package ID/version, content revision, audio SHA-256, MIME type, byte size, managed relative path, import time, and publication state.
- Revision-owned sentence, question, and choice snapshot rows: store the exact learner-visible and answer-key content for that revision, including timestamps, translations, explanations, evidence links, choice ordering, and correctness.
- Add `content_revision_id` to `listening_attempts`. At attempt creation, pin the currently published revision. Existing attempts are backfilled to a synthetic legacy revision created from the pre-v8 catalog inside the migration transaction.
- Preserve stable external manifest IDs as logical identifiers, but use revision-scoped internal keys for snapshot rows.
- Keep `listening_episodes` as the stable catalog identity/current-summary row. It must not be the historical source of truth for an attempt.
- Keep the currently published revision pointer separate from the immutable revision rows. Publishing a new revision changes only that pointer after all rows and audio are ready.

Before coding, inspect and explicitly migrate every existing foreign-key and uniqueness constraint. In particular, do not assume that adding a revision column alone is sufficient while answers still reference mutable question rows.

## 4. Import pipeline and commit boundary

### Phase A — no persistent mutation

1. Extract ZIP into a new private staging directory using `ListeningPackageArchive.extractSafely`.
2. Parse the manifest and run `ListeningContentPackageValidator.validate`.
3. Enforce package-version monotonicity and idempotency:
   - Same package ID + package version + content revision + identical manifest/audio digests: return AlreadyInstalled without writes.
   - Same package version with different content: reject as a package identity conflict.
   - Older package version: reject as downgrade unless a future explicit rollback policy is added.
4. Resolve each declared audio path beneath the canonical staging root.
5. Verify exact byte count and SHA-256 for every audio file.
6. Copy verified files to unique revision-scoped temporary destinations. Do not overwrite the currently published audio file.

### Phase B — database transaction

1. Start one SQLite write transaction.
2. Re-read package version/current revision under the transaction to close the race between validation and publication.
3. Insert source/license provenance records without deleting or weakening existing rights metadata.
4. Insert immutable revision header and all revision snapshot rows.
5. Validate all row counts, references, question cardinality, evidence ownership, and audio metadata again at the persistence boundary.
6. Mark the revision ready and update the episode's current-revision pointer in the same transaction.
7. Commit.

### Phase C — file publication and crash recovery

Database transactions cannot atomically commit filesystem renames. Use a small import journal/state machine rather than pretending the two systems share one transaction.

- Write new audio to unique revision-scoped paths and fsync it before the database transaction.
- The database transaction records the revision as `staged`, then publishes it only after every verified file path exists.
- If the process dies before the DB commit, orphaned revision files are safe to garbage-collect because no published revision references them.
- If the process dies after the DB commit, the published revision already references fully verified, durable files.
- Never overwrite or delete old revision audio during import.
- Cleanup of unreferenced old revisions is a separate maintenance operation with explicit retention rules; it must never run in the import transaction.

The implementation should prefer the ordering: verified immutable files first, then one atomic DB publication transaction. This makes a crash leave at worst harmless orphan files, not a published catalog row pointing at missing audio.

## 5. Attempts, assignments, and history reads

- `startAttempt` must read the current published revision and insert the attempt with that revision ID in one transaction.
- Question presentation for an attempt must query that attempt's revision snapshot, not the latest catalog.
- Answer submission must validate the choice against the same attempt revision.
- Score calculation must use the attempt revision's question count and answer key.
- Completed review must read selected choice, correct choice, explanation, and evidence from the pinned revision snapshot.
- Daily assignments should pin a revision when first presented. If no attempt has started, a product policy may allow an explicit refresh; once an attempt exists, the assignment and revision remain stable.
- Bookmarks remain attached to the stable episode identity unless a future product decision requires sentence-level revision pinning.
- Playback checkpoints remain guarded by audio revision/hash, as implemented in schema v7.

## 6. Required tests before enabling importer UI

### Idempotency and version policy
- Identical package import is a no-op.
- Same package version with changed digest is rejected.
- Older package version is rejected.
- A newer version updates the published pointer without changing older revision rows.

### Atomicity and recovery
- Invalid manifest, missing audio, wrong size, wrong SHA-256, disk write failure, SQL constraint failure, and simulated process interruption never publish a partial revision.
- Failed import leaves old episode metadata, audio, assignments, attempts, bookmarks, and playback state unchanged.
- A crash before DB commit may leave only unreferenced files; recovery removes them safely.
- A crash after DB commit finds all published audio files present and verified.

### Historical correctness
- Start and complete an attempt on revision A.
- Import revision B with changed transcript, question wording, choices, correct answer, evidence, and audio.
- Revision A review still shows the original transcript/question/options/correct answer/explanation/evidence and original score.
- New attempts use revision B.
- Old attempts and answers remain present and foreign-key integrity passes.
- A started daily assignment remains on revision A after revision B is imported.

### Regression and migration
- Fresh install creates v8 schema.
- v5, v6, and v7 upgrades preserve existing vocabulary data and listening rows.
- v7 migration snapshots all existing attempts into a legacy revision without changing score or answer history.
- Existing demo rows remain demo-only and are never published as formal content.

## 7. Implementation order

1. Finalize v8 schema and migration tests.
2. Add immutable revision read/write methods to the repository.
3. Bind attempts, answer submission, scoring, and review to revision snapshots.
4. Add importer orchestration with staged audio + single DB publication transaction.
5. Add rollback/crash-recovery and historical-revision instrumentation tests.
6. Only then expose package import in the UI.
7. Implement WorkManager download/sync after the local importer is reliable.

## 8. Explicit non-goals in this slice

- No APK build or release.
- No merge or rebase of PR #1.
- No production audio download or redistribution without verified rights.
- No automatic deletion of old revision audio.
- No rewrite of the existing vocabulary database or study flows.


## 9. Implementation status — schema v10 baseline

The first revision-safe persistence slice is now implemented on `feature/listening-mvp`:

- Schema v8 introduced immutable revision headers and revision-owned sentence/question/choice snapshots, with legacy attempts backfilled to the pre-upgrade content.
- Schema v9 added a revision pin to daily assignments and backfilled existing assignments.
- Schema v10 added immutable episode metadata and audio URI fields to each revision and backfilled legacy revision metadata.
- New attempts pin a revision transactionally. Today's daily assignment revision is preferred when the attempt starts from that assigned episode.
- Repository and Activity reads for the blind-listening, quiz and review stages now use attempt-scoped revision data. Answer validation, scoring and review are revision-scoped.
- Regression tests were added for post-attempt mutation of the live catalog.

This is a persistence and read-path foundation, **not a completed importer**. The next implementation must create new revision rows and snapshots from validated package content, stage each audio file at a unique revision-scoped path, and publish the episode pointer only after durable file verification. It must also solve removed-question lifecycle without deleting parent rows referenced by historical answers. No production course package should be considered installable until those importer and recovery tests exist.


## 10. Current implementation and verification handoff

Schema v10 and the attempt-scoped read path have been implemented. The current source has:
- immutable revision-owned sentences, questions and choices;
- immutable episode title/description/category/level/duration/accent/speech-rate/audio-URI metadata;
- current revision pointer on the stable episode;
- revision pinning on attempts and daily assignments;
- attempt-specific Repository methods consumed by MainActivity;
- scoring and review based on the pinned revision;
- migration and regression tests for legacy backfill and live-catalog mutation.

Source-level checks passed and a separate SQLite in-memory smoke simulation verified the legacy snapshot backfill, historical review query and foreign-key check. Android Gradle compilation and instrumentation have not yet been executed; no latest-head CI run is available. Treat runtime validation as pending.

The importer, package digest registry, revision-scoped audio publication journal, crash recovery, removed-question tombstone policy and WorkManager sync remain unimplemented. The next safe step is importer implementation on top of v10, with audio verification and atomic pointer publication; do not expose package installation until rollback and history-preservation tests pass.


## 11. Schema v11 importer implementation

The package import foundation is now implemented on the feature branch.

### Persistent catalog identity

- `listening_content_packages` stores one current package version and archive digest per package ID.
- `listening_package_episodes` records package ownership, the published revision, and whether the episode remains active in the package catalog.
- Episode ownership is unique across packages. A package cannot claim a legacy lesson or the system-TTS demo by reusing its episode ID.
- Lower package versions and same-version archives with different SHA-256 digests are rejected. An identical package is a no-op only after the active episode set, revision IDs, and every installed audio file are verified.

### Publication order

1. Extract the archive to a unique private staging directory with path, entry-count, decompressed-size, and manifest-size limits.
2. Validate the entire manifest and require exact equality between declared audio paths and extracted audio files.
3. Verify all audio byte counts and SHA-256 digests.
4. Install audio at revision-specific paths without replacing an existing immutable destination.
5. Begin one SQLite transaction; verify package version state again to detect a concurrent import.
6. Insert provenance records only when their identifiers are absent or their existing metadata matches exactly.
7. Create immutable revision metadata, transcript, questions, choices, and namespaced compatibility parent rows.
8. Update package ownership/active state and episode metadata.
9. Publish each episode's current revision pointer last, then commit.

If the transaction fails, newly written audio is deleted only when no revision row references it. The orphan scan can later remove unreferenced importer-owned revision files after a process interruption. Historical audio is retained.

### Lifecycle and compatibility

- Parent question/sentence IDs are unique per revision; this avoids collisions in legacy parent tables while preserving the foreign-key targets of historical answers.
- Existing parent rows are never deleted or replaced during package updates.
- Removed episodes are marked inactive for new catalog planning; old daily assignments and attempts remain resolvable through their pinned revision.
- The daily planner now reads current revision snapshots, so retained parent rows from prior revisions do not inflate question counts or invalidate new lessons.
- Formal package import explicitly refuses to convert a demo or unowned legacy episode into approved package content.

### Remaining validation gates

Instrumentation coverage now exercises package install, idempotency, version conflicts, downgrade rejection, demo protection, and historical revision retention. These tests are authored, not yet executed. We still need Android compilation, emulator instrumentation, injected transaction-failure/crash tests, and device playback checks. The importer is not exposed through UI and WorkManager/offline download orchestration remains out of scope until these gates pass.


## 12. In-progress attempt recovery and interaction motion

- `listening_attempts.stage` records the active stage (`blind` or `quiz`); completion sets it to `completed`.
- The Activity persists an active attempt ID and the current unsubmitted choice in preferences. The answer table remains the source of truth for submitted choices. If the preferences pointer is absent after a process interruption, the latest unfinished attempt is recovered from SQLite; starting another lesson marks the previous unfinished attempt as abandoned so it cannot be resurrected later.
- On restoration, the app loads the attempt's pinned revision, derives the first unanswered question from SQLite, restores a selected choice only if it belongs to that question, and recovers the correct count from persisted answers.
- If every question was answered before the app was interrupted but the completion transaction had not yet run, restoration completes the attempt and opens review.
- The user can resume the active unfinished lesson from the listening home screen. Dismissing a completed review clears only the resume pointer, not the historical attempt.
- Quiz evidence and review scoring use attempt-scoped revision data and persisted answer rows.

Stage changes now use a short, restrained content transition (115 ms exit / 220 ms entrance) with cubic easing and a small vertical offset. The navigation bar stays fixed; ordinary selection/feedback rerenders do not animate the entire screen. This is an initial motion pass, not a final sign-off: test reduced-motion settings, rapid repeated taps, interruption during animation, low-end devices, and long review pages before calling it complete.


## 13. Background playback across package updates

The playback service now validates a checkpoint against the immutable revision row identified by the media item's `episodeId`, `audio_url`, and `updated_at:audio_url` revision token. It no longer rejects an older, still-playing audio item merely because the catalog's current episode row points to a newer revision. The checkpoint is stored with the media item's own revision token, so it cannot be mistaken for progress on the new audio. Unknown, demo, and out-of-duration revision checkpoints are rejected.

The single-row playback table still stores one latest checkpoint per episode; switching revisions intentionally ignores a checkpoint whose token does not match the requested revision. Multi-revision playback history is not yet a separate feature.


## 14. Import snapshot and concurrency

The selected archive is copied into a unique private staging directory while its SHA-256 is computed. Extraction uses that exact private snapshot, not the original external file, eliminating a source-file mutation race between hashing and extraction. The importer caps compressed archive size at 1.1 GB in addition to ZIP entry-count, total decompressed-size, and manifest-size limits.

Import and orphan recovery share a process-wide lock. This prevents recovery from classifying a just-installed, not-yet-published revision file as an orphan while its import transaction is still in progress. The staging directory is cleaned even when the private archive snapshot itself fails.


## 15. Daily assignment UI pinning and scroll behavior

The home and intro screens resolve daily lesson metadata from the daily assignment's pinned revision, not from the latest mutable episode row. This keeps the displayed title/audio metadata consistent with the revision that a new attempt will actually pin. Quiz option/answer rerenders preserve the current listening scroll offset, while deliberate stage transitions reset the scroll position.


The importer checks free space before durable audio installation: total declared audio bytes plus a 64 MiB reserve must be available. A storage failure is raised before any revision audio is published.


## 2026-11-21 — Idempotency integrity follow-up

Same-version/same-digest import is not considered intact merely because the package registry matches and row counts are unchanged. The importer now verifies revision hash and metadata, package ownership/current pointer, approved non-demo status, audio path confinement and checksum, plus every immutable sentence, question, evidence link and choice field against the validated manifest. A regression test mutates an installed question prompt, requires the repeated import to reject the damaged snapshot, then restores the row and confirms normal idempotency. Android runtime execution remains pending.
