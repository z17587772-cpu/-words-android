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
