import json
import io
import urllib.error
import urllib.parse
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from discover_librivox import discover, extract_records, fetch_page, normalize_record


class DiscoveryTests(unittest.TestCase):
    def test_extract_records_accepts_documented_envelopes_and_empty_response(self):
        self.assertEqual([{"id": 1}], extract_records({"books": [{"id": 1}]}))
        self.assertEqual([{"id": 1}], extract_records({"book": {"id": 1}}))
        self.assertEqual([], extract_records({"error": "Audiobooks could not be found"}))

    def test_fetch_page_retries_rate_limit_and_then_succeeds(self):
        calls = []
        delays = []
        class Response:
            status = 200
            def __enter__(self):
                return self
            def __exit__(self, *_):
                return False
            def read(self):
                return b'{"books":[{"id":5,"title":"Retry"}]}'
        def opener(request, timeout):
            calls.append((request, timeout))
            if len(calls) == 1:
                raise urllib.error.HTTPError(
                    request.full_url, 429, "rate limited", {"Retry-After": "2"}, io.BytesIO(b"")
                )
            return Response()
        records = fetch_page(
            None, 0, opener=opener, sleeper=delays.append, jitter=lambda low, high: 0.0
        )
        self.assertNotIn("since", urllib.parse.parse_qs(urllib.parse.urlparse(calls[0][0].full_url).query))
        self.assertEqual(2, len(calls))
        self.assertEqual([2.0], delays)
        self.assertEqual(5, records[0]["id"])

    def test_missing_id_or_title_is_not_a_candidate(self):
        self.assertIsNone(normalize_record({"id": 7}, "2026-11-21T00:00:00Z"))
        self.assertIsNone(normalize_record({"title": "No ID"}, "2026-11-21T00:00:00Z"))

    def test_candidate_is_quarantined_for_rights_and_editorial_review(self):
        candidate = normalize_record({
            "id": 42, "title": "Example", "language": "English",
            "authors": [{"first_name": "A", "last_name": "Writer"}],
        }, "2026-11-21T00:00:00Z")
        self.assertEqual("REQUIRED", candidate["rights_review"]["status"])
        self.assertFalse(candidate["rights_review"]["offline_download_cleared"])
        self.assertEqual("DISCOVERED", candidate["editorial"]["status"])
        self.assertFalse(candidate["editorial"]["human_recorded_audio_verified"])

    def test_non_english_candidate_is_quarantined(self):
        candidate = normalize_record({
            "id": 43, "title": "Livre", "language": "French"
        }, "2026-11-21T00:00:00Z")
        self.assertEqual("QUARANTINED_NON_ENGLISH", candidate["triage"]["status"])
        self.assertEqual("QUARANTINED", candidate["editorial"]["status"])

    def test_repeated_discovery_preserves_editorial_decisions_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            now = datetime(2026, 11, 21, tzinfo=timezone.utc)
            pages = [[{"id": 9, "title": "A", "language": "English"}]]
            def fetcher(since, offset):
                return pages[0] if offset == 0 else []
            discover(root, now=now, fetcher=fetcher, sleep=lambda _: None)
            inbox_path = root / "inbox" / "librivox_candidates.json"
            inbox = json.loads(inbox_path.read_text())
            inbox["candidates"][0]["editorial"]["status"] = "REVIEW_REQUIRED"
            inbox["candidates"][0]["editorial"]["notes"] = "Editor note"
            inbox_path.write_text(json.dumps(inbox))
            discover(root, now=now, fetcher=fetcher, sleep=lambda _: None)
            updated = json.loads(inbox_path.read_text())["candidates"]
            self.assertEqual(1, len(updated))
            self.assertEqual("REVIEW_REQUIRED", updated[0]["editorial"]["status"])
            self.assertEqual("Editor note", updated[0]["editorial"]["notes"])

    def test_full_pages_are_rate_limited_and_offset_paginated(self):
        with tempfile.TemporaryDirectory() as tmp:
            offsets = []
            delays = []
            def fetcher(since, offset):
                offsets.append(offset)
                if offset == 0:
                    return [{"id": index + 1, "title": f"Book {index + 1}", "language": "English"} for index in range(500)]
                return [{"id": 500, "title": "Book 500", "language": "English"},
                        {"id": 501, "title": "Book 501", "language": "English"}]
            result = discover(
                Path(tmp),
                now=datetime(2026, 11, 21, tzinfo=timezone.utc),
                fetcher=fetcher,
                sleep=delays.append,
            )
            self.assertEqual([0, 500], offsets)
            self.assertEqual([3.0], delays)
            self.assertEqual(501, result["candidate_count"])
            self.assertEqual(502, result["records"])

    def test_incremental_cursor_uses_three_day_overlap(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            first = datetime(2026, 11, 21, 12, tzinfo=timezone.utc)
            discover(root, now=first, fetcher=lambda *_: [], sleep=lambda _: None)
            observed = []
            second = datetime(2026, 11, 22, 12, tzinfo=timezone.utc)
            result = discover(
                root, now=second,
                fetcher=lambda since, offset: observed.append((since, offset)) or [],
                sleep=lambda _: None,
            )
            expected = int(datetime(2026, 11, 18, tzinfo=timezone.utc).timestamp())
            self.assertEqual(expected, result["since_epoch"])
            self.assertEqual([(expected, 0)], observed)

    def test_full_catalog_bootstrap_resumes_from_persisted_page_checkpoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "state").mkdir()
            (root / "inbox").mkdir()
            (root / "state" / "librivox.json").write_text(json.dumps({
                "schema_version": 1,
                "provider": "librivox",
                "bootstrap_complete": False,
                "bootstrap_offset": 500,
                "last_success_utc": None,
            }))
            (root / "inbox" / "librivox_candidates.json").write_text(json.dumps({
                "schema_version": 1,
                "candidates": [{
                    "candidate_id": "librivox:1",
                    "provider_item_id": "1",
                    "editorial": {"status": "REVIEW_REQUIRED"},
                    "rights_review": {"status": "REQUIRED"},
                    "triage": {"status": "ELIGIBLE_FOR_EDITORIAL_TRIAGE"},
                    "discovered_at": "2026-11-20T00:00:00Z",
                }],
            }))
            observed = []
            def fetcher(since, offset):
                observed.append((since, offset))
                return [{"id": 501, "title": "Book 501", "language": "English"}]
            result = discover(
                root,
                now=datetime(2026, 11, 21, tzinfo=timezone.utc),
                fetcher=fetcher,
                sleep=lambda _: None,
            )
            self.assertEqual("full_bootstrap", result["mode"])
            self.assertEqual([(None, 500)], observed)
            inbox = json.loads((root / "inbox" / "librivox_candidates.json").read_text())
            self.assertEqual(2, len(inbox["candidates"]))
            state = json.loads((root / "state" / "librivox.json").read_text())
            self.assertTrue(state["bootstrap_complete"])
            self.assertEqual(0, state["bootstrap_offset"])

    def test_failed_page_does_not_advance_cursor_or_overwrite_inbox(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "state").mkdir()
            (root / "state" / "librivox.json").write_text(json.dumps({
                "schema_version": 1, "last_success_utc": "2026-11-20T00:00:00Z"
            }))
            before = (root / "state" / "librivox.json").read_text()
            calls = 0
            def fetcher(since, offset):
                nonlocal calls
                calls += 1
                if calls == 1:
                    return [{"id": 1, "title": "A"}] * 500
                raise RuntimeError("network error")
            with self.assertRaises(RuntimeError):
                discover(root, now=datetime(2026, 11, 21, tzinfo=timezone.utc), fetcher=fetcher, sleep=lambda _: None)
            self.assertEqual(before, (root / "state" / "librivox.json").read_text())
            self.assertFalse((root / "inbox" / "librivox_candidates.json").exists())


if __name__ == "__main__":
    unittest.main()
