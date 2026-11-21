# Listening Content Package Specification

Status: schema v1 validator, safe archive extraction, audio integrity, and a schema v11 revision-safe importer are implemented on the feature branch; Android runtime validation and offline sync remain pending.

## Package layout

A package is a ZIP archive with one manifest and relative audio files:

```text
package.zip
├── manifest.json
└── audio/
    ├── lesson-001.mp3
    └── lesson-002.m4a
```

The archive is data, never executable code. The app must not trust archive paths, manifest strings, remote URLs, or declared checksums.

## Manifest contract

The root object contains:

- `schema_version`: currently `1`; unknown versions are rejected.
- `package_id`, `package_version`, `minimum_app_version_code`.
- `content_revision`: immutable catalog revision label.
- `episodes`: one or more complete lessons.

Each episode includes stable `id`, title, description, category, level (L1-L4), duration, accent, speaker, audio descriptor, rights/provenance, sentence segments, and comprehension questions.

Audio descriptor fields:

- `path`: safe relative path under the package root; absolute paths, backslashes, empty segments, `.` and `..` are rejected.
- `size_bytes`: exact byte count, positive and no larger than 512 MiB.
- `sha256`: 64 hexadecimal characters.
- `mime_type`: an `audio/*` MIME type.
- `language`: must identify English.

Rights/provenance fields:

- stable `source_id`, `source_title`, optional `source_url`;
- `rights_holder`, `attribution`, `checked_at`;
- stable `license_id`, `license_name`, optional `license_url`;
- `allowed_actions` must explicitly include both `in_app_playback` and `offline_download`; imported course audio is stored locally for offline playback, so a streaming-only license is not eligible.
- The daily planner independently filters out any lesson whose license does not include both actions, so a streaming-only legacy catalog row cannot become a formal daily assignment.
- `territory` and optional `expires_at` (if present, later than `checked_at`).

Sentence IDs and question IDs must be unique within their episode. Sentence ordinals are sequential; timestamps must satisfy `0 <= start < end <= duration` and be chronological. Each lesson must contain 3-8 questions. Every question must have a supported type, 2-6 distinct choices, exactly one correct choice, a non-empty explanation, and an evidence sentence ID that exists in the same episode.

## Import transaction design

The implementation must follow this order:

1. Unzip into a private staging directory. Reject absolute paths, traversal, symlinks, duplicate archive entries, excessive entry count, and decompressed-size limits.
2. Parse and validate the entire manifest before opening a database write transaction.
3. Resolve every audio path beneath the canonical staging root.
4. Copy each audio stream to a temporary file in the destination directory while counting bytes and computing SHA-256. Flush and sync the temporary file.
5. Verify exact byte count and digest. Only then atomically move the file into its managed destination.
6. Insert or update source/license/episode metadata and all child rows in one SQLite transaction.
7. Commit only after every referenced audio file is verified and every database row is valid.
8. On failure, roll back metadata changes and remove newly staged files; never delete or replace the last known-good course audio before the replacement is verified.

The app's managed local audio and SQLite metadata are the source of truth for offline playback. Network sync is retryable and must not block access to already installed lessons.

## History and update safety

- Import must never use SQLite `REPLACE` for parent episodes, questions, or sentences: SQLite may delete the old row and cascade-delete attempts or answers.
- Re-importing an identical package revision must be idempotent.
- A changed lesson must not silently mutate questions referenced by completed attempts. The importer must preserve revision snapshots or reject unsafe in-place replacement until revision-aware history is implemented.
- Bookmarks, attempts, answers, first-attempt scores, and playback state must survive catalog refreshes.
- A package update must not replace an assignment that the learner has already started.
- Demo TTS material is not a package course and must never be promoted to `approved` by import.

## Current implementation boundary

Implemented:
- JSON manifest validation for schema v1, IDs, levels, timing, references, choices, rights, audio metadata, and app compatibility.
- Canonical-root path resolution.
- Audio copy with size and SHA-256 verification, temporary staging, and immutable revision-specific installation for package imports.
- ZIP extraction is limited to manifest/audio entries, rejects traversal/absolute/duplicate paths and caps entry count plus decompressed size; ZipInputStream materializes entries as regular files rather than filesystem symlinks.
- Instrumentation tests for malformed manifests, unsafe paths, checksum mismatch, preservation of the previous destination file on failure, and archive traversal rejection.

Not implemented yet:
- WorkManager catalog sync, download progress, retry, and offline library UI.


## Schema v11 importer update

Implemented on the feature branch:

- Package identity/version/archive-digest registry and package-to-episode ownership records.
- Idempotent identical-package handling after installed revision/audio verification.
- Downgrade and same-version/different-digest rejection.
- Exact manifest-to-archive audio path matching and license expiry checks at import time.
- Revision-specific immutable audio files verified before database publication.
- One SQLite transaction for revision snapshots and package/catalog publication, with the current revision pointer updated last.
- Revision-namespaced parent question/sentence IDs, preserving historical answer foreign keys without deleting prior rows.
- Package update deactivation for removed episodes; historical attempts and assignments keep their pinned revisions.
- Explicit protection against promoting the demo TTS lesson or taking over an unowned legacy episode.
- Orphan revision-audio scanning and regression tests for importer version/history behavior.

Still pending:

- Execute Android Gradle compilation and instrumentation tests on the emulator.
- Add injected failures at each import stage and verify transaction rollback plus orphan recovery.
- Validate playback of newly imported file URIs with Media3 on emulator and physical devices.
- Add WorkManager downloads, retry/resume, storage management, and offline-library UI.
- Do not expose package import in the learner UI until runtime and failure-injection gates pass.


The importer also hashes and extracts the same private archive snapshot (rather than re-reading the external source), caps compressed archives at 1.1 GB, and serializes import with orphan recovery to avoid cleanup races.
