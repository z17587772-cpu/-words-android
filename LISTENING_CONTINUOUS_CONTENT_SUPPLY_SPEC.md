# Listening Continuous Content Supply — Engineering Specification

Updated: 2026-11-21
Status: architecture and acceptance criteria; implementation is staged, not yet operational.
Target branch: `feature/listening-mvp`

## 1. Product invariants

1. A new lesson is a previously unseen learning unit, not a renamed episode, alternate crop, translated copy, or repackaged revision.
2. New lessons and review work are separate queues. A lesson that has been started must never be selected as a new lesson again.
3. An existing lesson revision update preserves the stable episode identity and all attempt/bookmark/playback history. It does not reset the lesson to unseen.
4. Raw source discovery is not course publication. Only reviewed, rights-cleared, quality-approved courses may enter the signed production catalog.
5. The app remains local-first. Network and publisher failures never disable verified local courses.
6. No TTS voice may be labelled or counted as human-recorded production audio.
7. No source dataset's code license is assumed to cover its audio, transcript, translation, or metadata. Rights are checked at the asset/item level and for the intended territories and actions.

## 2. End-to-end architecture

Source adapters -> candidate inbox -> provenance and deduplication -> audio/transcript processing -> editorial review -> immutable course package -> signed catalog -> Android download -> package validation/import -> new-lesson queue and independent review queue.

The publisher is independent from the Android APK. Adding a reviewed course must not require a new APK.

### Publisher responsibilities

- Run scheduled incremental discovery and a manual recovery run.
- Persist per-provider cursors and last successful sync timestamps.
- Retry transient network/rate-limit errors with bounded exponential backoff and jitter.
- Store raw source metadata and provenance before processing.
- Deduplicate idempotently.
- Keep rejected, quarantined, processing, and approved candidates distinct.
- Produce an audit trail for every processing and editorial decision.
- Publish immutable package versions and atomically update the signed catalog only after all artifacts are available and verified.
- Alert when the run fails, the candidate queue stalls, or the approved new-course reserve falls below thresholds.

### Android responsibilities

- Fetch only from a configured HTTPS catalog endpoint.
- Verify the catalog signature before trusting package URLs or metadata.
- Download packages with Android DownloadManager; use WorkManager for verification, installation, retries, and startup reconciliation.
- Verify exact byte size and SHA-256 before invoking the existing revision-safe importer.
- Never remove the last valid installed revision before the replacement is fully verified and committed.
- Reconcile missed download-completion broadcasts after process restart.
- Keep the verified local catalog usable when offline.

## 3. Source adapter contract

Each adapter must expose:
- stable provider key and provider item ID;
- provider item URL and source publication/update timestamp when available;
- language, locale/accent/speaker metadata only when supplied by the source;
- audio URL or controlled retrieval reference;
- transcript and transcript provenance when supplied;
- exact license identifier/URL, rights holder/attribution, territory, permitted actions, expiry/withdrawal conditions;
- incremental cursor and adapter version.

A provider adapter must not silently broaden an item's rights. Missing or ambiguous rights route the item to quarantine.

Initial adapter order:
1. LibriVox metadata discovery using its documented incremental API where possible. Audio is selected and reviewed per work/recording; public-domain status is not assumed to be worldwide.
2. Common Voice dataset import as a versioned batch with item-level provenance and the exact dataset release/license terms recorded. Dataset release cadence is not represented as a guaranteed daily supply.
3. Additional speech corpora only after their audio, transcript, and redistribution terms are checked independently.

LibriSpeech is useful as a finite, large source for read speech and alignment practice, but it is not a continuously refreshed conversational source. It should be treated as a backfill/seed corpus, not the sole long-term supply.

## 4. Candidate lifecycle

`DISCOVERED -> QUARANTINED or ELIGIBLE -> PROCESSING -> QA_FAILED or REVIEW_REQUIRED -> APPROVED -> PACKAGED -> PUBLISHED`

Terminal or side states:
- `REJECTED`: rights, quality, duplication, or educational suitability failed.
- `WITHDRAWN`: rights expired/revoked or source requests removal.
- `PROCESSING_FAILED`: retryable technical failure; preserve diagnostic details.

Only `PUBLISHED` courses enter the app's new-lesson pool. An approved candidate is not yet a published course.

## 5. Deduplication and identity

Use layered deduplication:
1. Unique constraint on (provider, provider_item_id).
2. Exact audio SHA-256 after download.
3. Perceptual audio fingerprint to identify re-encodes and alternate containers.
4. Normalized transcript similarity to detect overlapping passages and alternate cuts.
5. Editorial duplicate review for uncertain matches.

A stable episode ID represents the learning unit. A package revision represents a corrected/updated version of that unit. A new episode ID must not be generated just because title, translation, question set, audio encoding, or segmentation changed.

Do not automatically merge two candidates based only on transcript similarity: different speakers/accents may be pedagogically distinct. Similarity is a review signal, not an unconditional deletion rule.

## 6. Course quality gates

A course cannot be approved until all applicable checks pass:
- Human-recorded audio is explicitly verified for production lessons.
- Audio is playable, intelligible, complete, and within duration/size limits.
- Transcript is checked against the actual audio.
- Sentence timestamps are monotonic, non-negative, non-overlapping where required, and within audio duration.
- Chinese translation is complete and reviewed.
- Vocabulary selection is useful and level-appropriate.
- Each question has one unambiguous correct answer, plausible distractors, an explanation, and an evidence sentence.
- Question coverage is varied; avoid mechanically repeating the same question pattern.
- Level, topic, accent, speaker and duration metadata are supported by evidence.
- Source, rights holder, attribution, license, territory, permitted actions and review date are present.
- Audio and transcript similarity checks do not identify an unresolved duplicate.

Automation may generate drafts and run deterministic checks. Human editorial approval remains required for rights clearance and final educational/audio quality until a proven QA process is established.

## 7. Signed catalog and immutable package contract

Each package entry should include:
- package ID and immutable package version;
- schema version and minimum app version;
- package HTTPS URL;
- exact archive byte size and SHA-256;
- content revision identifier;
- episode IDs and revision metadata;
- source/provenance and license references;
- publication timestamp and key ID.

The catalog is canonicalized before signing. Use Ed25519 signatures with a private key held only in a protected publisher secret/store. The app pins the corresponding public key. Never commit a production private key or place it in the APK. Key rotation requires an explicit trust-transition plan and tests.

Publication order:
1. Upload immutable course package.
2. Download/read back and verify size and digest.
3. Generate and validate catalog.
4. Sign catalog.
5. Publish signed catalog atomically.
6. Record the publication event.

A package is never overwritten in place. Rollback publishes a new catalog revision pointing to a previously verified package.

## 8. New-lesson and review scheduling

New-lesson eligibility is based on stable episode identity and whether the episode has ever been assigned as a daily new lesson, not on package revision count or a rolling time window.
- `PUBLISHED + never assigned` -> new-lesson queue.
- Once an episode ID has appeared in any daily assignment, it is permanently excluded from the new-lesson queue, even if the learner skipped it. The original assignment remains accessible as a pending/previous lesson.
- A revision update keeps the same episode ID and never resets this exclusion.
- Started/completed content can be recommended only by the independent review planner.
- Newly published episode IDs immediately replenish the unseen pool.
- If no unseen published lesson exists, show an honest empty/new-content-unavailable state and offer review work. Never disguise review as a new lesson.
- Assignment history is checked across all dates, so device clock changes or backdated requests cannot recycle an already-assigned episode.

Review scheduling initially uses transparent, explainable rules from wrong answers, weak sentence segments, and elapsed time. A more complex scheduler such as FSRS is considered only after event data and calibration tests are available.

## 9. Reserve and operations policy

Initial targets (targets, not current inventory claims):
- Seed library: 30 approved new lessons plus 60 reusable review assets.
- Minimum approved unseen reserve: 14 days; target reserve: 30 days.
- Minimum net new releases: 7 per week; target: 10–20 per week after editorial capacity and rights are demonstrated.

Alerts:
- reserve below 14 days: increase discovery and review throughput;
- below 7 days: urgent editorial queue;
- below 3 days: show user-facing low-reserve state;
- zero unseen lessons: do not repeat as new; continue review queue.

A source's update frequency is not a guaranteed course production rate. Measure each stage separately: discovered, rights-cleared, processable, QA-passed, reviewed, published, and installed.

Scheduled automation must be idempotent and recoverable. Store run IDs, cursors, counts, errors, and timestamps. A failed daily job must be retried and visible; do not silently advance a cursor past unprocessed items.

## 10. Security, privacy, and reliability

- Use HTTPS and strict host allowlists for source and package retrieval.
- Validate all catalog and manifest fields before creating downloads.
- Defend against path traversal, archive bombs, duplicate ZIP paths, oversized files, digest mismatch, downgrade/replay, and untrusted package URLs.
- Treat source metadata and transcripts as untrusted input; never execute embedded content.
- Limit network requests and respect provider rate limits/terms.
- Do not collect speaker personal data beyond fields needed and permitted by the dataset terms.
- Preserve local learning history and old immutable revisions.
- Make installation atomic and idempotent; failure leaves the prior library intact.
- Add startup reconciliation for interrupted download/install work.
- Record app-visible sync status and a useful retry path without blocking offline study.

## 11. Required automated tests

- Same provider item discovered repeatedly creates one candidate.
- Same audio with a different filename/container is detected by digest or fingerprint.
- Similar transcript with different speaker is flagged for review, not automatically discarded.
- Missing/ambiguous rights cannot progress to approval.
- Expired/withdrawn rights prevent new publication and trigger the defined withdrawal policy.
- Failed processing does not advance the provider cursor past the failed item.
- A package with wrong signature, URL host, size, digest, schema, or version is rejected.
- Interrupted download resumes/reconciles without duplicate install.
- Failed install preserves the prior playable package and all user history.
- A package revision does not reset episode first-start state.
- A new episode enters the new-lesson queue; a started episode never does.
- Empty new-lesson queue never silently falls back to an old lesson as a new lesson.
- Offline mode continues to play verified local courses.
- A simulated 365-day run with weekly additions, outages, withdrawals, and failures preserves the invariants.

## 12. Delivery phases and exit criteria

### Phase A — New-vs-review invariant
- Separate new lesson eligibility from review eligibility.
- Add identity/first-start tests and catalog-growth tests.
- Exit: an episode cannot be assigned as a new lesson after it has been started.

### Phase B — Candidate ingestion
- Add provider cursor storage, LibriVox adapter, Common Voice batch importer, candidate schema and idempotent deduplication.
- Exit: repeated syncs are idempotent and all candidates have traceable provenance.

### Phase C — Editorial processing
- Add audio QA, transcript alignment, translation/question draft generation and review queue.
- Exit: no unreviewed candidate can be published.

### Phase D — Publisher and signed catalog
- Add immutable package publication, Ed25519 signing, catalog rollback and reserve metrics.
- Exit: catalog and package tampering is rejected by independent tests.

### Phase E — Android sync
- Add catalog verifier, DownloadManager coordination, WorkManager installer and startup reconciliation.
- Exit: a new course installs without APK update; failures preserve offline library.

### Phase F — Sustained operation
- Run staged soak tests, monitor reserve and content quality, expand sources only after license review.
- Exit: the system demonstrates sustained net-new approved lessons without false-new repeats or silent failures.

## 13. Explicit non-goals

- Automatically publishing raw scraped audio as a course.
- Treating public-domain status in one country as worldwide permission.
- Treating source repository licenses as audio licenses.
- Using TTS as a substitute for human-recorded production audio.
- Building the APK for every course update.
- Claiming a daily/weekly content quota before a real pipeline and editorial capacity prove it.


## 14. Implementation correction — strict no-repeat policy (2026-11-21)

The initial full-catalog rotation design would eventually repeat old episodes. That is not acceptable for the product requirement. The Android planner now uses a strict unseen-only policy: previously assigned stable episode IDs are excluded permanently from the new-lesson queue; when the unseen pool is empty it returns no new lesson. Review is explicitly separate. The repository history query includes assignments on all dates except the date being idempotently resolved, preventing backdated/future assignment edge cases from recycling a course.

Regression coverage now verifies:
- two initial lessons are assigned once;
- a backdated request cannot repeat a later-assigned lesson;
- a newly inserted episode is selected immediately;
- exhaustion returns no new lesson;
- another newly inserted episode replenishes the queue;
- changing title/audio/revision metadata for the same episode ID does not make it new again.
- a 371-day simulation with seven new episodes added weekly produces 371 unique assignments, then returns an empty new-lesson pool.

This is an assignment-level invariant. A separate UI/product pass must still ensure the empty state is presented honestly and that pending skipped assignments remain accessible without being re-labelled as new.


## 12. First operational discovery adapter — LibriVox (2026-11-21)

Implemented in `tools/content_pipeline/discover_librivox.py` with regression tests and a scheduled GitHub Actions workflow.

- First run backfills the complete catalog with `limit`/ `offset` pagination (maximum 500); subsequent runs use the documented `since` overlap. The bootstrap uses compact metadata (`extended=0`) to avoid downloading all per-section detail for every item.
- Fetches incrementally with a three-day UTC overlap and a three-second pause between full pages.
- Stores persistent state and a deduplicated candidate inbox on the separate `content-inbox` branch; the learner app and APK are not coupled to catalog discovery.
- Candidate identity is `librivox:<project_id>`; rediscovery preserves editorial, rights-review and triage decisions.
- Non-English candidates are quarantined.
- All candidates start with rights review required, with offline and in-app permissions false.
- Full-catalog bootstrap checkpoints the inbox and offset every five pages, then resumes from the last complete checkpoint after failure. Incremental cursor advances only after every page is fetched and the inbox is atomically written. Network/API failure exits non-zero without advancing the relevant cursor.
- The PR workflow runs adapter unit tests. The scheduled worker is configured for daily 03:17 UTC and starts operating after the workflow is present on the repository's default branch.

This adapter discovers metadata only. It does not download audio, determine worldwide public-domain status, generate a transcript/translation/questions, approve content, build course packages, sign a production catalog, or publish lessons. Those gates are intentionally not bypassed.
