# Words Listening — On-device Auto Discovery & Library Architecture

Updated: 2026-11-21
Status: in active implementation; first end-to-end raw-resource path added, validation pending
Target branch: `feature/listening-mvp`
APK packaging: intentionally paused

## 1. Correct product requirement

The app itself must continuously discover new listening resources on the public internet and save eligible resources into its own local listening library. The user must not need to install a new APK for ordinary content growth.

This is not satisfied by periodically polling one prebuilt catalog, a GitHub branch, or a manually curated package directory. A signed catalog remains useful only as an optional trusted distribution channel for reviewed structured courses.

## 2. Separate two kinds of content

### A. Discovered Listening Library (automatic, raw resource)

A separate local collection for source recordings that can be automatically discovered, legally downloaded, and played offline. It may contain a complete audiobook, short recording, or chapter/track. It does not pretend to be a fully authored lesson.

Minimum metadata:
- stable provider and provider item IDs;
- canonical source page and direct media URL;
- title, description, language, duration, author/speaker, category/tags;
- media format, byte size, SHA-256, and provider checksum where available;
- license/rights statement, attribution, territory and offline redistribution permission;
- discovery time, download time, provider revision/ETag/last-modified;
- normalized audio fingerprint where feasible;
- status: discovered, eligible, queued, downloading, downloaded, rejected, expired, failed.

Only items with an explicit, verified permission for in-app playback and offline storage are auto-downloaded. Ambiguous or territory-limited rights remain candidates and are not silently installed.

### B. Structured Course Library (reviewed learning content)

Existing `listening_episodes`, sentence, question and assignment models remain the formal course system. A raw recording is not promoted into a formal lesson until transcript, timestamps, Chinese translation, level, learning objectives, questions, answer evidence and educational QA are complete.

This preserves the current daily planner contract and prevents a long audiobook from appearing as a 5–10 minute Advanced lesson without being adapted.

## 3. In-app architecture

`ListeningDiscoveryScheduler` (WorkManager)
  -> `ListeningProviderRegistry`
  -> provider adapters (first: LibriVox API; second: Internet Archive Search + Metadata API)
  -> normalized `DiscoveredResource`
  -> eligibility + dedupe + ranking
  -> `ListeningResourceDownloadWorker` / Android DownloadManager
  -> checksum + media probe + safe local commit
  -> `listening_discovered_resources` and local media index
  -> offline player + discovered-library UI

The app performs provider API queries itself. No search-engine scraping, blind website crawling, or assumption that a public URL grants redistribution rights.

### Provider behavior

- Each adapter owns its endpoint, pagination, rate limit, retry/backoff, response parsing and stable ID rules.
- Persist provider cursors locally. Use overlap windows and idempotent upserts so crashes/retries do not lose new records.
- Respect provider rate limits and cache results. Do not repeatedly download the whole catalog.
- Query metadata first; fetch detailed metadata only for promising items.
- Use explicit provider allowlists and reject unsafe schemes, credential-bearing URLs, redirects to untrusted hosts, unsupported formats, excessive file sizes and malformed metadata.
- Prefer audio with a transcript/source text. Missing transcript is allowed in the raw library but blocks formal-course promotion.
- Avoid duplicate episodes by provider ID, canonical URL, content hash and (where feasible) normalized audio fingerprint. Do not use title-only matching as a destructive dedupe key.

### Initial provider candidates

1. LibriVox official API: paginated audiobook metadata and direct ZIP/RSS references. It is a strong first adapter for spoken-word recordings, but project metadata must still be checked for English, item suitability and territorial rights. The provider asks clients to paginate at no more than 500 records and pause several seconds between large pages.
2. Internet Archive: Advanced Search for discovery, Metadata API for item file metadata and checksums. The item-level license/rights metadata must be inspected; Archive.org hosting alone is not a license.
3. Add further providers only when they expose stable APIs, reliable item-level rights, downloadable audio, and suitable rate-limit/terms.

## 4. Automatic save policy

Default policy:
- Search automatically while network is available and device constraints permit.
- Save metadata candidates locally first.
- Automatically download only resources passing the explicit rights allowlist, English-language, supported-media, size/storage, source integrity and duplicate checks.
- Use Wi-Fi/unmetered by default; offer a user setting for mobile data later.
- Keep downloads resumable through DownloadManager; retry transient failures with bounded exponential backoff.
- Download into app-private staging, verify actual size and SHA-256/provider checksum, probe media duration/codec, then atomically publish the local file and database row.
- Never delete a previous valid copy until the replacement is verified.
- Maintain storage quotas and least-recently-used cleanup only for unpinned, already-downloaded resources.
- Keep favorites, completed listening records and active playback resources protected from cleanup.

## 5. Dedupe and long-term freshness

- Provider ID is the primary identity within one provider.
- Canonical URL is a secondary identity.
- SHA-256 identifies byte-identical files across providers.
- Optional acoustic fingerprint catches re-encodes and near-duplicate recordings; it must not merge merely similar readings.
- Store seen IDs and provider cursors indefinitely; use a bounded recent history for recommendations, not for identity.
- New daily material must come from not-yet-completed eligible resources; replay is review, not new content.
- Track title/author/topic/accent/voice variety to avoid a library dominated by one author or reading style.
- If a provider changes or removes an item, preserve the user's local copy and history unless a rights withdrawal requires removal.

## 6. What stays from the signed catalog work

Retain Ed25519 catalog verification, SHA-256, package compatibility and transactional course import for curated structured lessons. It is a secondary course-publishing lane, not the primary discovery engine. It must not be described as the implementation of automatic internet discovery.

## 7. Implementation phases and exit gates

### D0 — Product/data model
- [x] Add dedicated discovered-resource table and repository.
- [x] Add stable provider cursor/state storage.
- [x] Add status, rights, attribution and local-file metadata.
- [x] Keep raw resources separate from formal lesson tables.

Exit: schema migration tests prove existing vocabulary/listening history is unchanged.

### D1 — On-device provider discovery
- [x] Implement first on-device LibriVox metadata adapter.
- [x] Add Internet Archive Advanced Search + item Metadata API adapter with explicit license allowlist.
- [x] LibriVox pagination, request pacing, timeout, retry/backoff and persisted cursor.
- [x] Normalize English metadata and persist candidates idempotently.
- [x] Honor WorkManager cancellation during provider discovery.
- [ ] Add ETag/conditional requests and full network-fixture tests.

Exit: instrumented/fake-server tests prove pagination, restart recovery, duplicate handling, malformed response handling and provider rate limiting.

### D2 — Automatic download and safe local storage
- [x] Explicit item-level CC0 / CC-BY 4.0 / CC-BY-SA 4.0 allowlist; unlicensed LibriVox stays quarantined.
- [x] WorkManager audio download with unmetered-network and battery constraints.
- [x] SHA-256, exact provider byte-size, MP3 container/duration validation.
- [x] Immutable hash-versioned file publication and database pointer update after verification.
- [x] 512 MiB storage quota; evict only unpinned downloads not played in the last 30 days; serialize transfers to prevent quota races.
- [x] Reconcile missing local files, verify stored SHA-256 hashes, clean stale partial downloads and remove aged orphan MP3 files.
- [ ] Dedicated storage-management screen and user-directed deletion controls.

Exit: network interruption, process death, duplicate broadcasts, bad checksum and insufficient storage tests leave no corrupt library rows and preserve prior valid files. **Pending runtime verification.**

### D3 — Discovered-library UI/player
- [x] Add separate “自动发现资源库” list with source, attribution, duration and download state.
- [x] Add downloaded-only and favorites filters.
- [ ] Add title/source text search and dedicated storage-management controls.
- [x] Play downloaded resources offline and resume saved position/speed.
- [x] Display source and license attribution.
- [x] Clearly distinguish raw recordings from formal courses.

Exit: UI-flow tests cover discover -> download -> play offline -> favorite -> resume. Basic list/play/resume wiring is implemented; UI-flow instrumentation remains pending.

### D4 — Learning enrichment
- [ ] Transcript acquisition where a reliable source text exists.
- [ ] Optional speech-to-text only when technically and legally suitable; never fabricate transcript/timestamps.
- [ ] Sentence alignment, Chinese translation, level tagging and question authoring.
- [ ] Human QA before formal-course promotion.

Exit: only complete, reviewed lessons become eligible for daily assignment.

### D5 — Quality and release
- [ ] CI Android compilation and API 35+ instrumentation.
- [ ] Migration, provider, download, dedupe, storage, playback, accessibility and UI regression suites.
- [ ] Real device checks for battery, network, storage and process death.
- [ ] Only after all gates pass, build one upgraded APK containing the on-device discovery engine.
- [ ] Subsequent eligible resource additions happen in-app without APK updates.

## 8. Explicitly not considered complete

The Python LibriVox worker remains a metadata-only GitHub Actions harvester. The Android LibriVox adapter remains metadata-only and rights-quarantined. The Internet Archive adapter now performs item-level CC license filtering, downloads a bounded MP3 track per item, verifies it and publishes it to a separate local library. The listening screen can list, filter, favorite and play downloaded raw resources offline. This is not yet a complete course-authoring pipeline: transcript acquisition, sentence alignment, Chinese translation, level tagging and question authoring still require separate processing and QA. The signed-catalog worker remains a separate fixed-endpoint course distribution lane.

