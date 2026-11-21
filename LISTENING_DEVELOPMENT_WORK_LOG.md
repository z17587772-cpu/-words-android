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
