# Listening Development Work Log

## 2026-11-21 — Requirement correction and architecture audit

### User clarification
The desired behavior is for the Android app itself to search online listening resources and automatically save eligible resources into its local library. It is not sufficient to poll a fixed catalog or require repeated APK releases for content updates.

### Historical requirements recovered
- Advanced is the core target; progression L1–L4.
- Daily main practice 5–10 minutes; complete session 15–25 minutes; around five questions.
- Daily task plus weekly library expansion.
- Training loop: blind listening -> questions -> evidence/incorrect-fragment review -> sentence-level listening -> transcript/translation -> dictation or shadowing -> vocabulary links -> targeted review.
- Balanced topics, avoid repeated resources, preserve learning history.
- APK construction remains paused until the feature work is complete and reviewed.

### Code audit findings
- `tools/content_pipeline/discover_librivox.py` is a Python metadata-only LibriVox harvester. It paginates, retries, checkpoints and deduplicates candidates while preserving editorial decisions. It runs in a GitHub Actions workflow, not inside Android.
- `tools/content_pipeline/README.md` explicitly says the worker does not download audio, infer worldwide public-domain status, create questions or publish course packages.
- `ListeningCatalogSync.kt` is a signed fixed-catalog downloader. It verifies Ed25519, HTTPS host, package digest and safe import. It is a content transport/security layer, not a general web discovery engine.
- Existing formal course importer/planner expects structured lessons with transcript, timestamps, reviewed translation, questions and rights. Raw online recordings cannot be inserted into that schema without weakening the course quality gates.
- A separate raw/discovered-resource library is therefore required, alongside the existing reviewed course library.

### Engineering decision
Adopt the two-lane model documented in `LISTENING_AUTO_DISCOVERY_ARCHITECTURE.md`:
1. On-device discovery and automatic download of rights-cleared raw recordings into a separate local resource library.
2. Reviewed structured courses remain a separate lane and are promoted only after educational and rights QA.

### External source verification
- LibriVox API documents `since`, `limit` (maximum 500), `offset`, JSON format and metadata fields including audio ZIP/RSS/source text URLs.
- LibriVox asks bulk API users to paginate and pause several seconds between requests.
- LibriVox states its recordings are public domain, while source-text public-domain status may differ by territory; app-side item/territory review is still necessary.
- Internet Archive provides an item Metadata API for file and metadata retrieval; hosting on Archive.org is not itself proof of reuse rights.

### Changes made this work session
- Added `LISTENING_AUTO_DISCOVERY_ARCHITECTURE.md` with the corrected product contract, provider model, automatic download policy, dedupe, storage safety, phased implementation and exit gates.
- Added this durable work log.
- APK packaging remains paused. No claims of Android runtime/CI success are made.

### Next implementation tasks
1. Inspect actual WordsDatabase migration/version and existing listening UI navigation.
2. Add `listening_discovered_resources` and provider cursor persistence with migration tests.
3. Implement Android LibriVox API adapter with pagination/rate limiting/retry/idempotent upsert.
4. Add Internet Archive adapter only after rights/metadata parsing and rate-limit tests.
5. Connect eligible resources to DownloadManager and safe atomic local storage.
6. Build discovered-resource UI and offline playback.
7. Add provider fake-server tests, database migration tests, failure injection, emulator and physical-device verification.

### Validation status
- Python discovery worker has unit tests in repository, but current Android on-device discovery is not implemented.
- Existing Android catalog sync compile/instrumentation status remains unverified from the available CI result.
- No APK was built.

### Implementation progress — on-device discovery foundation

- Bumped `WordsDatabase` to schema v12 and added `listening_discovered_resources` plus `listening_provider_state`. The migration is additive; vocabulary tables and existing course/attempt rows are untouched.
- Added `ListeningDiscoveryRepository`: stable provider identity, idempotent metadata refresh, cursor persistence, status tracking, local file metadata, and fail-closed download eligibility. A provider rediscovery cannot overwrite a prior cleared/rejected rights decision, pin, installed path, or download state.
- Added `ListeningDiscovery.kt`: a WorkManager scheduler that starts an immediate LibriVox metadata search and repeats daily; first run backfills a bounded recent window, later runs use an overlap cursor, at most five 50-item pages per run, three-second spacing, and bounded response size/timeouts.
- Added the on-device worker call to `MainActivity` startup.
- Added Android instrumentation coverage for schema v5 -> v12 migration, parser normalization and host/language filtering, dedupe/state preservation, and rights-gated download queue behavior.
- Extended PR CI static assertions to check the new worker, schema, scheduler and tests.
- Important boundary: this implementation currently saves discovered metadata candidates into the local database. It does not yet download the LibriVox ZIP/RSS audio, unpack chapters, display a discovered-resource screen, or provide a separate raw-resource player. Those are explicit next tasks, not completed functionality.
- The current LibriVox adapter intentionally marks rights as `review_required` and grants no download actions. This prevents treating the provider's general public-domain statement as universal item/territory permission.
- CI verification: no workflow run was returned for the latest commit at the time of this log. Android compilation and instrumentation tests are therefore pending, not passed.
- APK packaging remains paused.

### Follow-up static review

- Isolated LibriVox JSON normalization into a pure parser so parser tests do not need to construct WorkManager internals.
- Made host validation explicitly null-safe.
- Aligned detected MIME type with the actually accepted ZIP/RSS URL, rather than the mere presence of an untrusted URL field.
- Tightened the repository invariant: generic status updates cannot mark a resource downloaded; only `saveLocalFile` after verified local publication can do so.
- Rechecked the PR workflow assertions: schema version 12, new scheduler, parser, repository, and instrumentation test names are now included.
- CI still reports no run for the latest feature commits in the available workflow-run lookup. Compilation and instrumentation remain unverified; do not treat static inspection as a passing build.

### 2026-11-21 — Automatic discovery to offline playback implementation

- Added a second on-device source adapter using Internet Archive Advanced Search and the item Metadata API. It revisits the newest search results every day and rotates through a bounded historical backfill. Metadata requests are paced and response sizes, identifiers, file formats, file sizes and HTTPS hosts are constrained.
- Automatic offline downloads are limited to item metadata that explicitly declares CC0 1.0, CC BY 4.0 or CC BY-SA 4.0. A generic Archive.org URL or LibriVox's general project statement is not enough. LibriVox candidates remain quarantined for item/territory review.
- Added WorkManager download tasks requiring an unmetered network and battery-not-low. The downloader follows only HTTPS Archive.org subdomains, checks provider-declared exact byte size, computes SHA-256, probes the MP3 with MediaMetadataRetriever, and publishes an immutable hash-versioned local file only after verification.
- Added repository APIs for download candidate reads, local library listing, favorites, and playback checkpoint persistence.
- Added database v13 migration with a separate raw-resource playback progress table. Existing vocabulary, formal listening lessons, and learning history are not reused or rewritten.
- Added the “自动发现资源库” section to the listening home. It shows discovery/download status, source/license attribution, favorites, and local playback. Raw audio is not promoted to formal daily lessons.
- Extended the existing Media3 playback service to persist raw-resource position and speed. Reopening a downloaded recording resumes from the last checkpoint; a recording at its end restarts from zero.
- Added tests for CC license allowlisting, English language normalization, resource library listing and playback checkpoint persistence. Updated CI source assertions and bumped the app version to 1.8.2 / versionCode 23 (APK build intentionally paused).
- Safety/quality caps: at most five candidate tracks are queued per discovery run; individual MP3 files are capped at 20 MiB; audio transfers require unmetered connectivity.
- Important remaining gates: no successful Android Gradle compile or instrumentation result has been observed yet. Network integration tests against the live Archive API, storage quota/cleanup, orphan reconciliation, cancellation, and physical-device playback remain outstanding. The current implementation is a substantial first end-to-end path, not yet a verified release.

- Follow-up storage hardening: capped the discovered-audio library at 512 MiB, serialized downloads through one WorkManager unique chain, and only evicts unpinned recordings that have never been played. Metadata and learning history remain; evicted items can be manually downloaded again. Pinned and previously played files are protected.
- The Internet Archive query now covers MARC21 `eng`, `English`, and `en` language metadata values; the license parser accepts both scalar and array metadata values. This was aligned with the IA metadata docs, which define `licenseurl` as the item's license field and describe language as a provider-supplied metadata value.

- Discovery fairness fix: the Internet Archive adapter now maintains a 30-day per-item checked cursor, revisits newest page 1 each run, and advances a separate historical backfill page only after that page is scanned. This prevents the newest already-seen items from consuming every run and starving the historical cursor.
- To keep each WorkManager execution bounded, the adapter performs at most three item-metadata lookups per page and six per run, queues at most four tracks per run, and selects one best-sized MP3 track per item. Invalid item metadata is skipped; transient HTTP/network errors still trigger retry/backoff.
- Added downloaded-only and favorites filters, source-page navigation, explicit attribution text, and current storage usage to the in-app library.
- Storage cleanup now protects pinned items and anything played in the last 30 days; older unpinned copies may be evicted while playback position/metadata remain for manual re-download and resume.

- Added a local reconciliation worker on startup and daily: it repairs database rows whose files disappeared or changed size, queues re-download when rights still allow it, removes stale partial files after six hours, and removes unreferenced MP3 orphans after a 24-hour grace period. It checks canonical paths before touching files and does not delete paths outside the app-private library.
- Added cross-source SHA-256 reuse: when another provider yields byte-identical audio, the app reuses the verified local file and groups identical library cards. Storage accounting counts shared files once; eviction removes one metadata reference at a time and deletes bytes only after the last reference is gone.
- Added source-license revocation handling. If an Internet Archive item loses its explicit approved license or no longer meets the supported media/language policy, cached offline/playback permission is revoked and the local pointer is cleared. Shared bytes remain only while another valid source record references them.
- Added regression coverage for cross-source hash reuse, missing-file recovery, and license revocation. The app still preserves formal lesson history separately.

- Added source-level permission revocation: cached resources are immediately made unavailable when item-level license metadata is removed/changed, when the item becomes inaccessible, or when it no longer meets the supported English/MP3 policy. The reconciler later removes unreferenced bytes after the orphan grace period.
- Added WorkManager stop checks in both provider discovery workers. The local reconciler now verifies stored SHA-256 hashes as well as exact file size and repairs missing/corrupt records by re-queueing only when offline rights remain valid.

- Added age-appropriate metadata screening to the Internet Archive adapter. It checks title, description and subject metadata and excludes clearly adult/explicit material before download; this is a conservative metadata filter, not a guarantee that every source item is perfectly classified.
- Added title/creator/provider search in the resource library, alongside downloaded-only and favorites filters. The UI also exposes source and license pages.

- Final metadata polish: source title/creator/description values are normalized from scalar or array fields and HTML tags are stripped before display. The age-appropriate filter checks title, description and subject fields.


## 2026-11-21 — Storage eviction correction, manual cleanup and motion polish
- Fixed a real storage-eviction predicate mismatch: resources played more than 30 days ago were returned as eviction candidates but rejected by the final SQL update. The update now uses the same 30-day cutoff and still protects pinned/recently played items.
- Changed eviction ordering so the database row is detached before physical deletion. A concurrent play/pin update can no longer leave a downloaded row pointing to a file already removed. Failed physical deletion is left for the orphan reconciler and is not counted as freed quota.
- Added user-directed local-audio deletion in the discovered library. It validates the canonical app-private path, confirms before deletion, preserves metadata/favorite/playback history, and keeps shared audio bytes while another provider record references them.
- Added repository regression coverage for 31-day eviction and manual deletion with shared-file/history preservation.
- Refined the vocabulary card flip from 520 ms to 430 ms and slightly lengthened swipe settle/commit timing to reduce abruptness while retaining the existing easing curve.
- Runtime compilation/instrumentation is still required; no APK was built.


## 2026-11-21 — Final source-level verification checkpoint
- Re-fetched the changed repository, download worker, MainActivity, reconciliation worker, instrumentation test and CI workflow from the branch head.
- Source-level assertions passed for: matching 30-day eviction eligibility, DB-first eviction ordering, rights-revocation race guard, confirmed app-private local deletion, shared-file preservation, regression-test presence, CI assertion coverage, card flip timing and SHA-256 reconciliation.
- Added an explicit boundary assertion that a resource played today is protected, while one last played 31 days ago is eligible for eviction.
- The PR head currently has no associated GitHub Actions run. Therefore Gradle compilation, instrumentation execution, live provider integration and device playback remain unverified; no success is claimed.
- Branch comparison remains divergent from main (560 commits ahead, 8 behind). No merge or rebase was attempted.
- APK packaging remains paused.


## 2026-11-21 — Validation-gated APK pipeline
- Updated app version to 1.8.3 / versionCode 24 for the next installable build.
- Added a branch-push trigger for the feature branch to the listening validation workflow, in addition to PR and manual triggers.
- The workflow now runs the existing Python checks, static policy assertions, Kotlin app/test compilation and API 35 instrumentation suite first; only after those gates pass does it assemble and upload a debug APK artifact.
- This intentionally does not claim that an APK has been built. The workflow must complete successfully and produce its artifact before a download can be provided.
- The APK is a debug-signed build. Because each clean hosted runner normally generates its own debug keystore, update-in-place across builds is not guaranteed; a stable private release keystore is needed for a proper signed upgrade path. No signing key was committed to the repository.
