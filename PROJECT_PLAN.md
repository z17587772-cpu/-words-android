# words Android — Optimization Plan

## Product direction

The app has two independent learning modes:

1. **随刷 (Quick Browse)** — an always-available, unlimited vocabulary stream for short idle moments. It never asks for familiarity ratings, does not consume the daily study quota, and does not alter the spaced-repetition state. Users can reveal the word, meaning, pronunciation, and examples at their own pace, then move on or skip.
2. **背词 (Study Session)** — the deliberate learning workflow. It keeps the daily goal, concealed/revealed flashcards, "认识 / 模糊 / 不认识" feedback, review scheduling, and study statistics.

The modes may share wordbook data, importance metadata, favorites, and text-to-speech, but their session state and metrics remain separate.

## Implementation sequence

- [ ] Reconstruct and inspect the current Android project.
- [ ] Promote the reconstructed project to a normal, readable repository directory; document the build and keep the source archive only as a migration fallback.
- [ ] Add a prominent Quick Browse entry to the main navigation/home surface.
- [ ] Add an endless weighted sampler with no adjacent/recent repeats, while isolating it from study progress.
- [ ] Preserve the original study-session behavior and its review scheduler.
- [ ] Improve the vocabulary card proportions, typography, spacing, navigation transitions, and launcher icon.
- [ ] Audit wordbook schema, deduplication, importance tiers, and frequency evidence; label inferred rankings separately from verified corpus frequency.
- [ ] Build and validate a debug APK; publish the artifact and document known limitations.

## Ranking policy

Priority tiers must be traceable to an explicit source. Exam relevance and curriculum scope can be used as ranking features. Corpus-frequency counts are only called "verified" when the corpus and counting method are documented. Where evidence is unavailable, use a transparent estimated priority score instead of inventing a frequency number.

## Build

The reproducible GitHub Actions workflow is `.github/workflows/android-apk.yml`. The debug APK is produced by `gradle clean assembleDebug`.
