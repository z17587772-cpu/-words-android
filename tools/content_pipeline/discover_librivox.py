#!/usr/bin/env python3
"""Incremental, conservative LibriVox metadata discovery.

This tool only creates editorial candidates. It never downloads audio, asserts
worldwide public-domain status, creates lessons, or publishes app content.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

API_URL = "https://librivox.org/api/feed/audiobooks"
USER_AGENT = "WordsListeningContentDiscovery/1.0 (metadata-only; contact via repository)"
PAGE_SIZE = 500
REQUEST_DELAY_SECONDS = 3.0
OVERLAP_DAYS = 3


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_utc(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("Cursor timestamps must include a timezone")
    return parsed.astimezone(timezone.utc)


def extract_records(payload: Any) -> list[dict[str, Any]]:
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    if not isinstance(payload, dict):
        raise ValueError("Unexpected LibriVox response type")
    for key in ("books", "book", "audiobooks"):
        value = payload.get(key)
        if isinstance(value, list):
            return [item for item in value if isinstance(item, dict)]
        if isinstance(value, dict):
            return [value]
    if payload.get("error") == "Audiobooks could not be found":
        return []
    raise ValueError("LibriVox response did not contain a recognized records field")


def fetch_page(
    since_epoch: int,
    offset: int,
    opener=urllib.request.urlopen,
    sleeper=time.sleep,
    jitter=None,
) -> list[dict[str, Any]]:
    import random

    params = urllib.parse.urlencode({
        "format": "json",
        "extended": "1",
        "limit": str(PAGE_SIZE),
        "offset": str(offset),
        "since": str(since_epoch),
        "sort_order": "asc",
    })
    request = urllib.request.Request(
        API_URL + "?" + params,
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
    )
    jitter = jitter or random.uniform
    raw = None
    for attempt in range(4):
        try:
            with opener(request, timeout=45) as response:
                status = getattr(response, "status", 200)
                if status != 200:
                    raise RuntimeError(f"LibriVox API returned HTTP {status}")
                raw = response.read()
            break
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return []
            if exc.code not in (429, 500, 502, 503, 504) or attempt == 3:
                raise RuntimeError(f"LibriVox API returned HTTP {exc.code}") from exc
            retry_after = exc.headers.get("Retry-After") if exc.headers else None
            try:
                delay = min(60.0, max(1.0, float(retry_after))) if retry_after else min(30.0, 2.0 ** attempt + jitter(0.0, 1.0))
            except ValueError:
                delay = min(30.0, 2.0 ** attempt + jitter(0.0, 1.0))
            sleeper(delay)
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            if attempt == 3:
                raise RuntimeError(f"LibriVox API request failed after retries: {exc}") from exc
            sleeper(min(30.0, 2.0 ** attempt + jitter(0.0, 1.0)))
    if raw is None:
        raise RuntimeError("LibriVox API request exhausted retries without a response")
    try:
        return extract_records(json.loads(raw.decode("utf-8")))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError("LibriVox API returned invalid JSON") from exc


def normalize_record(raw: dict[str, Any], discovered_at: str) -> dict[str, Any] | None:
    raw_id = raw.get("id")
    if raw_id is None or not str(raw_id).isdigit():
        return None
    project_id = str(int(raw_id))
    title = str(raw.get("title") or "").strip()
    if not title:
        return None
    language = str(raw.get("language") or "").strip()
    authors = raw.get("authors") or []
    genres = raw.get("genres") or []
    sections = raw.get("sections") or []
    if isinstance(authors, dict):
        authors = [authors]
    if isinstance(genres, dict):
        genres = [genres]
    if isinstance(sections, dict):
        sections = [sections]

    return {
        "candidate_id": f"librivox:{project_id}",
        "provider": "librivox",
        "provider_item_id": project_id,
        "title": title,
        "description": str(raw.get("description") or "").strip(),
        "language": language,
        "catalog_date": str(raw.get("date_catalog") or "").strip(),
        "copyright_year": raw.get("copyright_year"),
        "duration_seconds": raw.get("totaltimesecs"),
        "section_count": raw.get("num_sections"),
        "authors": authors if isinstance(authors, list) else [],
        "genres": genres if isinstance(genres, list) else [],
        "sections": sections if isinstance(sections, list) else [],
        "source_urls": {
            "catalog": str(raw.get("url_librivox") or "").strip(),
            "rss": str(raw.get("url_rss") or "").strip(),
            "archive": str(raw.get("url_iarchive") or "").strip(),
            "audio_zip": str(raw.get("url_zip_file") or "").strip(),
            "text": str(raw.get("url_text_source") or "").strip(),
        },
        "rights_review": {
            "status": "REQUIRED",
            "reason": "LibriVox source status does not establish worldwide rights for this app's intended territories and offline redistribution.",
            "territory_cleared": [],
            "offline_download_cleared": False,
            "in_app_playback_cleared": False,
            "reviewed_at": None,
        },
        "triage": {
            "status": "ELIGIBLE_FOR_EDITORIAL_TRIAGE" if language.casefold() == "english" else "QUARANTINED_NON_ENGLISH",
            "reason": "" if language.casefold() == "english" else "The listening course is English-only; confirm language metadata before any further processing.",
        },
        "editorial": {
            "status": "DISCOVERED" if language.casefold() == "english" else "QUARANTINED",
            "human_recorded_audio_verified": False,
            "transcript_verified": False,
            "translation_reviewed": False,
            "questions_reviewed": False,
            "reviewer": None,
            "notes": "",
        },
        "discovered_at": discovered_at,
        "adapter_version": 1,
    }


def read_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def atomic_write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def discover(root: Path, now: datetime | None = None, fetcher=fetch_page, sleep=time.sleep) -> dict[str, int]:
    now = (now or utc_now()).astimezone(timezone.utc)
    state_path = root / "state" / "librivox.json"
    inbox_path = root / "inbox" / "librivox_candidates.json"
    state = read_json(state_path, {})
    inbox = read_json(inbox_path, {"schema_version": 1, "candidates": []})
    if inbox.get("schema_version") != 1 or not isinstance(inbox.get("candidates"), list):
        raise ValueError("Candidate inbox schema is invalid")

    last_success = state.get("last_success_utc")
    if last_success:
        lower_bound = parse_utc(last_success) - timedelta(days=OVERLAP_DAYS)
    else:
        bootstrap_days = int(os.environ.get("LIBRIVOX_BOOTSTRAP_DAYS", "30"))
        if not 1 <= bootstrap_days <= 365:
            raise ValueError("LIBRIVOX_BOOTSTRAP_DAYS must be between 1 and 365")
        lower_bound = now - timedelta(days=bootstrap_days)
    # LibriVox compares the 'since' filter at UTC-day granularity.
    lower_bound = lower_bound.replace(hour=0, minute=0, second=0, microsecond=0)
    since_epoch = int(lower_bound.timestamp())

    existing = {str(item.get("candidate_id")): item for item in inbox["candidates"] if isinstance(item, dict)}
    discovered_at = iso_utc(now)
    offset = 0
    pages = 0
    fetched = 0
    while True:
        records = fetcher(since_epoch, offset)
        pages += 1
        fetched += len(records)
        for raw in records:
            candidate = normalize_record(raw, discovered_at)
            if candidate is None:
                continue
            key = candidate["candidate_id"]
            if key in existing:
                # Preserve all editorial and rights decisions on repeated discovery.
                prior = existing[key]
                candidate["editorial"] = prior.get("editorial", candidate["editorial"])
                candidate["rights_review"] = prior.get("rights_review", candidate["rights_review"])
                candidate["triage"] = prior.get("triage", candidate["triage"])
                candidate["discovered_at"] = prior.get("discovered_at", discovered_at)
            existing[key] = candidate
        if len(records) < PAGE_SIZE:
            break
        offset += PAGE_SIZE
        sleep(REQUEST_DELAY_SECONDS)

    ordered = sorted(existing.values(), key=lambda item: (item.get("catalog_date", ""), int(item["provider_item_id"])))
    atomic_write_json(inbox_path, {"schema_version": 1, "candidates": ordered})
    # Cursor advances only after every page was fetched and the inbox was atomically saved.
    atomic_write_json(state_path, {
        "schema_version": 1,
        "provider": "librivox",
        "last_success_utc": iso_utc(now),
        "last_since_epoch": since_epoch,
        "last_run_pages": pages,
        "last_run_records": fetched,
        "candidate_count": len(ordered),
    })
    return {"pages": pages, "records": fetched, "candidate_count": len(ordered), "since_epoch": since_epoch}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("content-pipeline"))
    args = parser.parse_args()
    try:
        result = discover(args.root)
    except Exception as exc:
        print(f"Discovery failed; cursor was not advanced: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
