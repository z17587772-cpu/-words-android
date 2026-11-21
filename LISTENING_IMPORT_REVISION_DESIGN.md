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
