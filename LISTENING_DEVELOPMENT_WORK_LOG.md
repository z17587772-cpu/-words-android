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


## 2026-11-21 — Disk-accurate quota and persistent signing support
- Storage quota enforcement and the visible library usage figure now count actual files under the canonical app-private listening-library root, including orphan MP3s and partial downloads. Symlink targets outside the root are excluded. This prevents a failed deletion or stale partial file from being invisible to quota accounting.
- Missing files contribute zero physical bytes when eviction recalculates capacity.
- Added instrumentation coverage for orphan/partial accounting, missing roots, and an optional external symlink.
- Added optional persistent APK signing support through GitHub Actions secrets: WORDS_SIGNING_KEYSTORE_BASE64, WORDS_SIGNING_STORE_PASSWORD, WORDS_SIGNING_KEY_ALIAS, WORDS_SIGNING_KEY_PASSWORD. No keystore or password is stored in source control. If these are absent, the CI APK uses the runner's normal debug signing key.
- The validation workflow assembles and uploads the APK only after compile and emulator tests succeed. A successful workflow run and artifact are still not visible in the current GitHub status data.


## 2026-11-21 — Final CI workflow self-check
- Corrected the workflow's own ordering assertion so it searches for the actual YAML step anchors (newline + step name), not the explanatory strings inside the Python assertion itself.
- Rechecked the actual YAML ordering: emulator instrumentation step precedes APK assembly, which precedes artifact upload.
- Rechecked Gradle version 1.8.3 / versionCode 24 and optional persistent signing configuration.
- Current-head Actions status is still empty through the available GitHub status/run views; the compile, emulator suite and APK artifact have not been observed. This is explicitly not recorded as a successful build.


## 2026-11-22 — Persistent handoff, vocabulary rollback protection, and APK production pause

### User instruction
- Keep a complete, durable record of each session's objective, implementation plan, changed files, commits, test evidence, and unfinished work in GitHub.
- Do not generate or publish a new APK until the entire project is complete.
- Keep the current app version at 1.8.5 / versionCode 26 while APK production is paused.

### Work completed in this session
- Added a project-wide handoff and execution baseline in `PROJECT_PLAN.md`; each future session must read it, write a plan first, commit implementation/tests, and close by updating the work log and project status.
- Fixed the build pipeline so all 11 source wordbooks are copied to Android assets with ID/count/field/hash checks.
- Added database schema v14 `books.content_sha256`; same-version changed content updates, lower versions never downgrade, and matching word IDs plus reviews/history remain preserved.
- Added regression tests for same-version hash changes and all 11 built-in pack database seeding/idempotence.
- 1.8.5 / versionCode 26 is retained as requested; README's stale code 25 value was corrected.
- Paused APK production:
  - `.github/workflows/listening-pr-check.yml` retains source compilation and emulator checks but no longer assembles/signs/uploads an APK.
  - `.github/workflows/android-apk.yml` manual build job is disabled with a job-level false condition.
  - CI has static assertions that protect the no-APK policy and require the manual job to stay disabled.
- Updated `README.md`, `PROJECT_PLAN.md`, `android/PROJECT_STATUS.txt` and this work log to make the packaging pause and retained version explicit.
- Updated PR #1's description with durable project status and the no-release boundary; PR remains Draft.

### Relevant commits
- `28aa8a9` project-wide plan and handoff rules.
- `5509f92` copy and verify all wordbooks in Android assets.
- `afce141` schema v14 / content-hash upgrade logic.
- `247ca76` content-hash, downgrade and history-preservation tests.
- `fce557c` APK-internal wordbook inventory verification (historical packaging validation).
- `9dc4d97` verified schema v14 and asset audit checkpoint.
- `7b8ff30`, `081ac4e` all-11-pack database seeding regression (test implementation/refinement).
- `d19eb77` retain/set version 1.8.5 / code 26.
- `fed9a1b` enforce seed regression and version metadata in CI.
- `e8e8950` synchronize README, project status and plan for 1.8.5.
- `df24a11` remove APK assembly/sign/upload from normal validation.
- `379af2d` disable manual APK-production job.
- `cec35de` add CI guard for manual APK job being disabled.
- `e5a56de` fix self-referential no-APK assertions.

### Validation evidence and limitations
- Run #100 (`45029773333`) passed schema v14 instrumentation, APK archive/metadata/signature checks and direct wordbook inventory validation; it produced the historical 1.8.4 / code 25 artifact.
- Run #100 artifact `words-validated-debug-apk-100`, ID `14756944617`; it contains all 11 wordbooks and 50,698 entries. This is evidence for the wordbook packaging repair only; it is not a 1.8.5 install package.
- Older intermediate runs for 1.8.5 were cancelled as later commits superseded them.
- Latest validation-only run after APK production was disabled: [run 45030655437](https://github.com/z17587772-cpu/-words-android/actions/runs/45030655437). It must be checked for success; no APK artifact is expected.
- The schema-v14 database migration and same-version hash logic passed the prior run's tests. The new all-11-pack database seeding/idempotence test was added afterward and still needs its completed run result.
- No physical PJX110 Android 16 install/upgrade test has been performed. No claim of real-device validation is made.

### Next work
1. Inspect the result of validation-only run #45030655437; fix any CI/static assertion or instrumentation failure without re-enabling APK production.
2. Complete the all-11-pack database seeding test: verify pack IDs/counts/content hashes, 50,698 active rows, and idempotent second initialization.
3. Continue the P1 vocabulary/swipe/UI regression plan in `PROJECT_PLAN.md`.
4. Continue listening download observability (byte progress, total size, failure codes, pause/cancel/resume tests) and course quality/supply work.
5. Keep normal CI validation active. Do not generate APKs, sign, upload, publish a release, or change version 1.8.5 / code 26 unless the user explicitly authorizes release after project completion.


## 2026-11-22 — Pause all APK production; retain version 1.8.5 / code 26

### User instruction
- No new APK is to be generated or published until the entire project is complete.
- The app version must stay at 1.8.5 / versionCode 26 during the pause.

### Changes
- Removed APK assemble/sign/artifact upload steps from `.github/workflows/listening-pr-check.yml`. The workflow continues to run Python checks, source checks, Kotlin compilation and Android emulator instrumentation.
- Disabled the manual build job in `.github/workflows/android-apk.yml` with a job-level false condition so it cannot accidentally produce APKs.
- Added CI policy assertions: normal validation must not contain APK build/upload operations, and the manual APK job must remain disabled.
- Corrected a GitHub Actions expression interpolation issue in the assertion. Run #45030655437 failed at the first version of the policy assertion because GitHub expanded the `${{ false }}` literal before Python saw it. Commit `5011c86` now constructs the dollar sign in Python to avoid expression expansion.
- Corrected README versionCode from stale 25 to 26 and updated README, project plan, project status, work log and PR description to state that APK production is paused.

### Relevant commits
- `df24a11` — remove APK production from normal validation workflow.
- `379af2d` — disable manual APK workflow job.
- `cec35de` — add CI guard for the paused APK policy.
- `e5a56de` — fix initial self-referential no-build assertions.
- `5011c86` — fix expression interpolation in the manual-workflow guard.

### Verification
- Older run `45030392698` on an unpaused workflow was cancelled after the workflow change; it must not be used as evidence for a release.
- Run `45030655437` failed only in the static policy check because the workflow expression was expanded; the failure log was inspected.
- Latest correction is committed, and a fresh validation-only run must pass before the pause policy can be called verified.
- No new APK is expected from any subsequent development validation run.
- Version remains 1.8.5 / code 26.

### Next actions
1. Confirm the latest validation-only Actions run passes the static no-APK guards, Kotlin compile and API 35 instrumentation tests.
2. Keep the no-package workflows in place throughout feature development.
3. Continue UI, vocabulary and listening improvements under `PROJECT_PLAN.md`; update the work log at the end of each work session.
4. Only restore APK production after all product/device gates pass and the user explicitly authorizes release.


## 2026-11-22 — Full project inventory, historical-plan reconciliation and unified roadmap

### Scope
- Reviewed branch history, current PR/branches, main-vs-feature divergence, current Actions validation and both APK-production workflow controls.
- Read the project plan/status/README, the 34-section listening roadmap, append-only work log, architecture review, automatic discovery architecture, continuous supply specification, content-package specification, revision importer design, source-rights evaluation, and vocabulary source/curriculum audits.
- Inspected current Android database, vocabulary scheduler, listening discovery/resource automation, package importer, signed catalog client, Media3 playback service, daily/review planners, instrumentation tests and workflows.
- Reconciled documented history against preserved user requirements and current source/CI evidence; checked relevant Android and learning-science references.

### Verified baseline
- Branch: feature/listening-mvp
- HEAD: 91b7b8b58e15297ae5e0aa096c21cfea37553b1d
- Compare with main: 633 commits ahead / 8 behind; 48 changed files; no merge/rebase performed.
- PR #1 remains Draft; main remains untouched.
- Version remains 1.8.5 / code 26.
- Latest current-HEAD validation-only run [45030879471](https://github.com/z17587772-cpu/-words-android/actions/runs/45030879471) succeeded: Python pipeline, 11-pack sync/asset checks, source boundary checks, Kotlin app/test compilation and API 35 emulator instrumentation all passed.
- No artifact was produced by the current run, as required.
- 11 wordbooks sum to 50,698 pack records (not globally unique terms); curriculum source keys 3,099 vs 3,097 app rows after case-insensitive merging.
- Current test inventory: 38 Android @Test methods in five instrumentation classes and 18 Python test methods in two content-pipeline test files. android/core-tests is not included in that total because it is not confirmed as an active Gradle test module.

### Reconciled decisions
- Roadmap section 22 strict no-repeat rule supersedes section 18's older “repeat oldest after full pass” behavior.
- A discovered/downloaded raw audio resource is not a published formal lesson. Rights, transcript/timing, translation, evidence-linked questions, quality review and signed publishing are separate gates.
- LibriVox Android candidates stay rights-quarantined; Python catalog discovery is metadata-only and its scheduled workflow is not active on main while PR #1 remains Draft.
- Internet Archive raw-audio discovery/download is partly implemented with metadata/license gates, but live long-run provider behavior, user-facing byte progress, pause/cancel and failure recovery need more tests.
- Signed catalog client/importer exists but remote sync remains disabled because the pinned public key is empty; content-inbox has no production catalog or course ZIP assets.
- Current download heartbeat is not true byte progress/percentage.
- The PJX110 ZIP corruption report remains unresolved until device-side file SHA-256 is compared and installation is tested.
- Stable signing continuity is unverified; protect local learning data and do not use uninstall as default troubleshooting.

### Unified roadmap
Created PROJECT_AUDIT_AND_MASTER_ROADMAP.md to unify:
- all previous user requirements and project memory;
- branch/history and documentation inventory;
- current code vs. tested vs. designed-but-disabled vs. not-yet-built;
- source/rights boundaries and 11-pack vocabulary facts;
- conflicts among old task lists and the final strict no-repeat rule;
- phases for wordbook QA, vocabulary/UI regression, raw audio reliability, formal course supply, new-course/review experience and Android 16 physical-device/release acceptance.

### Documentation-only changes
- The audit is the new single authoritative current status and master roadmap.
- PROJECT_PLAN points to the audit and must not retain stale “latest run pending” language.
- PROJECT_STATUS is normalized to the current run and no-artifact status.
- README's direct APK build commands are removed while APK production is paused.
- LISTENING_PRODUCT_ROADMAP is explicitly labelled as historical snapshots; original details are retained.
- No application source code, database schema, dependency, version number or APK was changed/created in this audit.

### Next execution order
1. 11-pack content-quality audit and issue report.
2. Home/daily study/flashcard UI regression matrix and statistics verification.
3. Raw-audio download state machine, true byte progress and failure injection.
4. Rights/editorial/production pipeline and signed course catalog activation.
5. Strict no-repeat new lessons, separate review and full listening learning loop.
6. Android 16 physical-device, performance, accessibility, data-upgrade and release gate.

APK production remains paused; version 1.8.5 / code 26 remains fixed.


## 2026-11-22 — 全项目审计整合与文档基线校准

### 本轮目标与计划
- 汇总历次对话中的产品要求、项目记忆、代码实现、历史计划、执行记录和测试证据。
- 对照当前仓库文件，区分已实现、已验证、未验收和仅有设计方案的事项。
- 裁决历史路线冲突，建立唯一主路线和跨会话接手规则。
- 保持 APK 生产暂停，版本固定 1.8.5 / versionCode 26。

### 已完成的文档整理
- 新增 `PROJECT_AUDIT_AND_MASTER_ROADMAP.md`：统一历史需求、词书数据与来源口径、词汇/随刷现状、听力架构与阻塞、旧计划文件索引、测试证据、外部技术决策、分阶段执行路线和完成标准。
- 更新 `PROJECT_PLAN.md`：明确审计文件为全项目执行权威入口，保留会话纪律和阶段任务。
- 更新 `android/PROJECT_STATUS.txt`：收敛当前实现、CI 证据、测试规模、真机缺口和发布暂停状态。
- 更新 `LISTENING_PRODUCT_ROADMAP.md`：保留历史计划，但标注旧 checklist 不等同于当前状态，并以统一审计为准。
- 更新 `README.md`：加入项目交接与当前状态入口。
- 当前分支审计基线校准为 `bbe08129aa7e097c413e9fd6af2e69ef31d6ef99`；最新同步文档提交对应 Actions run 45034367082 成功，artifact 数量为 0，符合暂停 APK 生产的要求。

### 关键结论
- 11 套词书共 50,698 条 pack records；这不是跨词书去重后的唯一词数。
- 词书构建同步缺陷已修复；数据库 schema v14 的内容哈希更新、防降级和学习记录保留测试已通过 API 35 模拟器 CI。
- 原始音频发现/下载和审核后正式课程是两条独立链路；正式课程生产尚未激活。
- 下载 heartbeat 不是真实字节百分比；需要补齐可观测下载状态机。
- 严格 no-repeat 新课规则优先于早期“耗尽后循环旧课”的历史计划。
- PJX110 Android 16 真机安装、ZIP 异常定因、稳定签名连续性仍未关闭。

### 当前验证
- [Actions run 45034367082](https://github.com/z17587772-cpu/-words-android/actions/runs/45034367082)：成功；本次仅文档更新，APK artifacts 为 0。
- 当前不生产、不签名、不上传、不发布 APK；版本号保持 1.8.5 / 26。

### 下一步
- 按统一审计 Phase 1 开始词书语义与字段质量审计；先提交逐项计划和验收条件，再执行数据扫描与抽样。
- 不得将设计文档、历史提交或模拟器通过误报为真机/内容生产验收。
