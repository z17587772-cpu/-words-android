# words Android — optimization implementation record

## Product decision

The app now has two independent modes:

1. **随刷 (Quick Browse)** — unlimited continuous vocabulary browsing for idle moments. No familiarity questions, no daily quota, and no writes to spaced-repetition state.
2. **背词 (Daily Study)** — deliberate learning with daily goal, reveal flow, 不认识 / 模糊 / 认识 feedback, review scheduling and study statistics.

The modes share wordbook data, favorites and TTS, but each has independent queues, state and metrics.

## Implementation checklist

- [x] Reconstruct and inspect the existing Android project.
- [x] Normalize source into a conventional `android/` tree and remove transport-only source_chunks/ and source_archive/ clutter.
- [x] Make 随刷 the default first navigation entry.
- [x] Add an unlimited weighted browse cycle without in-cycle repeats and with a recent-view cooldown.
- [x] Keep 背词 as a separate daily workflow with its original mastery/review logic.
- [x] Enlarge vocabulary cards and refine layout hierarchy, spacing, transitions and launcher icon.
- [x] Add SQLite v1-to-v2 migration for corpus metadata without deleting review history/favorites.
- [x] Add pinned third-party combined-corpus frequency data and document its evidence limits.
- [x] Build and validate the debug APK; upload artifacts and publish Release v1.2.0.

## Ranking evidence

- `importance` is the original curriculum/list tier, not a past-paper count.
- `corpus_frequency` and `frequency_rank` come from a pinned third-party corpus combining Gaokao papers and high-school textbooks.
- The upstream source does not fully disclose every source text and tokenization/lemmatization rule; therefore the app does not call these official or exhaustive Gaokao-only frequencies.
- Unmatched records remain -1 / blank. No invented zero-frequency values are created.
- Core curriculum extraction has 2,974 entries, leaving approximately 126 records to reconcile against the stated ~3,100-word target.

## Build and delivery

- Canonical Android project: `android/`.
- Source wordbooks: `wordbooks/`; scripts: `tools/`.
- Workflow: `.github/workflows/android-apk.yml`.
- Output: debug-signed `words.apk`, published in GitHub Release `v1.2.0`.