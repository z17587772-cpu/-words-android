# words — Android English vocabulary app

An offline-first Android vocabulary app for high-school English learners.

## Product structure

The app has two deliberately separate learning modes:

- **Quick Browse / 无限刷词**: an open-ended, low-friction vocabulary stream. It has no daily quota and does not ask learners to self-rate every word. Users can reveal meanings, inspect examples, listen to pronunciation, skip, favorite, and continue indefinitely.
- **Study / 系统背词**: a structured spaced-repetition workflow with daily goals, progress, and the existing "认识 / 模糊 / 不认识" response model.

These modes must not share a session queue or mastery state in a way that causes quick browsing to alter scheduled study progress.

## Repository layout

- `.github/workflows/android-apk.yml` — reproducible Android build.
- `wordbooks/` — offline JSON wordbooks and source/licence notes.
- `source_chunks/` — legacy transport fragments used to reconstruct the Android source archive. This is a compatibility layer, not the preferred long-term source layout.
- `docs/` — product, data, architecture, and maintenance notes.

The build reconstructs the Android project under `words-android/`, injects the wordbooks into assets, then builds a debug APK.

## Wordbook data status

- `gaokao_standard_2025.json`: 2,974 identifiable, deduplicated entries extracted from the 2025-revised curriculum appendix. The published appendix specifies 3,100 words; the current extraction gap is 126 and remains explicitly documented.
- `highschool_extension_3743.json`: 3,743 unique headwords derived from a 6,008-row BSD-3-licensed source list.

Curriculum tier markers are not the same as measured examination frequency. Until a traceable past-paper corpus is analyzed, any priority value must be labeled as a curriculum-tier/learning-priority estimate, not an empirical Gaokao frequency rank.

## Build and download

Every push to `main` and manual workflow dispatch builds `app-debug.apk`. Open the repository's Actions tab, select the latest successful **Build words APK** run, and download the `words-debug-apk` artifact.

The APK is a debug build, not a Play Store release.

## Maintenance principles

1. Keep the source tree human-readable and buildable; avoid editing only opaque transport fragments.
2. Keep wordbook source attribution and licence files alongside the data.
3. Validate JSON schema, duplicate headwords, missing meanings, and word counts before building.
4. Keep quick browsing independent from scheduled study.
5. Do not claim statistical exam-frequency rankings without a reproducible source corpus and method.
