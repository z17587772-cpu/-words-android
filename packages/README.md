# Approved listening course packages

Place only reviewed production ZIP packages in this folder before listing them in `../catalog/unsigned_catalog.json`.

Requirements:

- Each ZIP must contain a schema-v1 `manifest.json` and its referenced `audio/*` files.
- The manifest must pass the Android content-package validator.
- Every episode must have verified human-recorded audio, complete transcript and reviewed Chinese translation, sentence timestamps, reviewed questions and evidence, source attribution, and explicit rights for in-app playback and offline download in the intended territories.
- The ZIP must be no larger than 90 MiB for the current raw-GitHub hosting adapter.
- The catalog URL must be the corresponding raw-content URL on the `content-inbox` branch.

Never place unreviewed LibriVox candidates, Common Voice dataset dumps, or other raw source archives here.
