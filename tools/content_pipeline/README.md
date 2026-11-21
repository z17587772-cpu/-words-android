# Listening content pipeline

## Current operational boundary

The first provider adapter is an incremental **metadata-only** LibriVox discovery worker. It is deliberately not an audio downloader or course publisher.

Run locally:

```bash
python -m unittest discover -s tools/content_pipeline -p 'test_*.py' -v
python tools/content_pipeline/discover_librivox.py --root content-pipeline
```

The scheduled GitHub Actions workflow runs daily at 03:17 UTC and stores its persistent state and candidate inbox on the `content-inbox` branch. It can also be started manually from the Actions tab after the workflow exists on the default branch.

## Reliability behavior

- Uses LibriVox's documented `since`, `limit`, `offset`, `extended`, and JSON API parameters.
- Never requests more than 500 records per page and waits at least three seconds between full pages.
- Uses a three-day overlap because LibriVox compares `since` at UTC-day granularity.
- Deduplicates by stable provider ID and preserves editorial, rights-review, and triage decisions on rediscovery.
- Writes candidate inbox first and advances the cursor only after all pages are fetched successfully.
- A network/API failure exits non-zero and does not advance the cursor.
- Non-English items are quarantined from the English course workflow.
- Every item starts with rights review required and offline/in-app permissions false.

## Human gates

Discovery does not mean content is usable. Before any course package can be produced, a reviewer must verify:
- language and source identity;
- recording and transcript suitability;
- exact item-level rights for the intended territories and offline redistribution;
- attribution and source-text status;
- transcript, timing, translation, vocabulary, questions and answer evidence;
- human-recorded audio status.

The source adapter does not download audio, infer worldwide public-domain status, create learning questions, or publish signed catalogs. Those stages remain separate so raw public metadata cannot silently become an app lesson.
