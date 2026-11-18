#!/usr/bin/env python3
"""Attach a pinned, third-party corpus-frequency reference to bundled wordbooks.

Curriculum importance remains separate. The external source combines Gaokao
papers and high-school textbook material; its counts are NOT pure official
Gaokao frequency counts.
"""
from __future__ import annotations

import json
import re
import urllib.request
import zipfile
from io import BytesIO
from pathlib import Path

SOURCE_COMMIT = "676ae3cfecfc77e29c2e22317efa8e153aff9c45"
SOURCE_REPO = "ZhangYixuan-in-BUPT/english-word-frequencies"
SOURCE_LABEL = f"{SOURCE_REPO}@{SOURCE_COMMIT}"
ARCHIVE_URL = (
    "https://raw.githubusercontent.com/"
    f"{SOURCE_REPO}/{SOURCE_COMMIT}/word_frequencies_data.zip"
)
LICENSE_URL = (
    "https://raw.githubusercontent.com/"
    f"{SOURCE_REPO}/{SOURCE_COMMIT}/LICENSE"
)


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "words-android-wordbook-builder/1.0"})
    with urllib.request.urlopen(request, timeout=45) as response:
        return response.read()


def normalize_word(value: object) -> str:
    return re.sub(r"\s+", " ", str(value or "").strip().casefold())


def records_from_json(payload: object) -> list[dict]:
    if isinstance(payload, list):
        return [row for row in payload if isinstance(row, dict)]
    if isinstance(payload, dict):
        for key in ("words", "data", "items", "word_frequencies"):
            candidate = payload.get(key)
            if isinstance(candidate, list):
                return [row for row in candidate if isinstance(row, dict)]
    return []


def load_frequency_map(archive_bytes: bytes) -> dict[str, tuple[int, str]]:
    with zipfile.ZipFile(BytesIO(archive_bytes)) as archive:
        json_paths = [name for name in archive.namelist() if name.lower().endswith(".json")]
        if not json_paths:
            raise RuntimeError("frequency archive contains no JSON file")
        best: list[dict] = []
        for name in json_paths:
            try:
                payload = json.loads(archive.read(name).decode("utf-8-sig"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                continue
            candidate = records_from_json(payload)
            if len(candidate) > len(best):
                best = candidate
        if not best:
            raise RuntimeError("could not locate a word-frequency JSON array")

    result: dict[str, tuple[int, str]] = {}
    for record in best:
        word = normalize_word(record.get("word"))
        try:
            frequency = int(record.get("frequency", -1))
        except (TypeError, ValueError):
            continue
        rank = str(record.get("frequency_rank", "")).strip().lower()
        if not word or frequency < 0 or rank not in {"high", "medium", "low"}:
            continue
        result[word] = (frequency, rank)
    if not result:
        raise RuntimeError("frequency JSON had no valid (word, frequency, rank) rows")
    return result


def enrich(pack_path: Path, frequency: dict[str, tuple[int, str]]) -> tuple[int, int]:
    pack = json.loads(pack_path.read_text(encoding="utf-8"))
    words = pack.get("words")
    if not isinstance(words, list):
        raise RuntimeError(f"{pack_path}: missing words array")
    matched = 0
    for item in words:
        key = normalize_word(item.get("word"))
        hit = frequency.get(key)
        if hit is None:
            item["corpus_frequency"] = -1
            item["frequency_rank"] = ""
            item["frequency_source"] = ""
        else:
            item["corpus_frequency"], item["frequency_rank"] = hit
            item["frequency_source"] = SOURCE_LABEL
            matched += 1
    # Bump the built-in asset pack version so v1 installs are upgraded in-place.
    pack["version"] = max(2, int(pack.get("version", 1)) + 1)
    pack_path.write_text(json.dumps(pack, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return matched, len(words)


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    books_dir = root / "app/src/main/assets/books"
    licenses_dir = root / "app/src/main/assets/licenses"
    books = sorted(books_dir.glob("*.json"))
    if not books:
        raise RuntimeError(f"no wordbook JSON files found in {books_dir}")

    print(f"Downloading pinned frequency reference: {SOURCE_LABEL}")
    frequency = load_frequency_map(fetch(ARCHIVE_URL))
    print(f"Loaded {len(frequency):,} valid frequency records")
    total_matched = 0
    total_words = 0
    for path in books:
        matched, count = enrich(path, frequency)
        total_matched += matched
        total_words += count
        print(f"{path.name}: {matched:,}/{count:,} matched ({matched / count:.1%})")

    licenses_dir.mkdir(parents=True, exist_ok=True)
    (licenses_dir / "english-word-frequencies-MIT.txt").write_bytes(fetch(LICENSE_URL))
    print(f"Total: {total_matched:,}/{total_words:,} matched ({total_matched / total_words:.1%})")
    print("NOTE: these counts refer to the referenced combined exam + textbook corpus, not an official complete Gaokao-only corpus.")


if __name__ == "__main__":
    main()