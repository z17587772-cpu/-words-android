# Listening Source Evaluation & Rights Gate

Updated: 2026-11-21
Status: engineering source-selection decision; not legal advice

## Decision summary

The production catalog must contain complete, reviewed learning units, not merely speech-recognition clips. A source being open, downloadable, or marked CC0 does not by itself prove that the app may redistribute its audio in downloadable course packages in every target territory.

| Source | Useful material | Decision for production courses | Reason |
|---|---|---|---|
| LibriVox API | Full audiobook projects, sections, RSS/audio references, source metadata | Candidate discovery only; per-work rights review required | The API supports `since`, `limit` (maximum 500), and `offset`. LibriVox asks clients to use several-second delays. Copyright status must be reviewed for the work, recording, territories, and intended offline redistribution. |
| Mozilla Common Voice Scripted Speech 27.0 English | Large collection of read-speech clips; useful for ASR and pronunciation research | Do not mirror, bundle, or redistribute its dataset audio as app lessons under the current access terms | The dataset listing marks the corpus CC0, but the Common Voice terms effective 2025-10-31 state that datasets are made available exclusively through Mozilla Data Collective and ask users not to post, distribute, or mirror datasets elsewhere. |
| Mozilla Common Voice Spontaneous Speech 5.0 English | Spontaneous short responses; more conversational than scripted clips | Research/evaluation candidate only; not a production-course feed | Short isolated responses are not equivalent to a coherent 5–10 minute course, and the same distribution-channel restriction applies. |

## Important distinctions

1. **Copyright/license and platform access terms are separate checks.** Record both the dataset license and the applicable access/distribution terms. Do not infer permission to mirror or re-host from a CC0 label alone.
2. **Clip collections are not complete lessons.** Common Voice clips are useful for speech-recognition evaluation, accent exposure, and short pronunciation drills, but do not independently provide coherent long-form context, reviewed Chinese translation, sentence timing, question coverage, answer evidence, or course pedagogy.
3. **LibriVox discovery is not approval.** Keep the current metadata-only adapter. Never auto-download or auto-publish solely because an item appears in LibriVox.
4. **No source may bypass editorial review.** Production publication still requires human-recording verification, exact item-level rights and attribution, transcript/audio alignment, translation review, pedagogical QA, duplicate checks, and immutable package validation.

## Implementation implications

- Keep LibriVox as a metadata-only candidate adapter with stable provider IDs, resumable pagination, overlap-based incremental sync, and rights quarantine.
- Do not add a Common Voice downloader or copy its MP3/TSV corpus into the repository, APK, or app-hosted course packages under the current terms.
- If Common Voice is ever evaluated for internal research, first verify the exact dataset release, locale, terms, access conditions, and permitted processing; keep any permitted use separate from production publishing.
- Prioritize sources that provide coherent, human-recorded long-form material with explicit redistribution rights for the intended territories, or commission original recordings with signed contributor releases.
- Track source suitability separately for: discovery, internal evaluation, in-app streaming, offline download, redistribution, and derivative educational packaging.

## Source references

- LibriVox API information: https://librivox.org/api/info
- LibriVox API update (2026-09-16): https://librivox.org/2026/09/16/librivox-api-update/
- Mozilla Common Voice dataset catalog: https://commonvoice.mozilla.org/en/datasets
- Mozilla Common Voice legal terms (effective 2025-10-31): https://commonvoice.mozilla.org/en/terms

