from __future__ import annotations

import hashlib
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audit_wordbooks import inspect_payload


class WordbookAuditTests(unittest.TestCase):
    def sample(self):
        payload = {
            "id": "sample",
            "name": "Sample",
            "version": 1,
            "words": [
                {
                    "word": "alpha",
                    "meaning": "n.  甲；adj. 第一的",
                    "phonetic": "/ˈælfə/",
                    "pos": "n./adj.",
                    "example": "",
                    "example_zh": "",
                    "corpus_frequency": -1,
                    "frequency_rank": "",
                    "importance": 1,
                },
                {
                    "word": "beta",
                    "meaning": "n. 乙；n. 乙",
                    "phonetic": "",
                    "pos": "",
                    "example": "",
                    "example_zh": "",
                    "corpus_frequency": 12,
                    "frequency_rank": "high",
                    "importance": 2,
                },
            ],
        }
        raw = b'{"id":"sample","words":[{"word":"alpha"},{"word":"beta"}]}'
        return payload, raw

    def test_counts_optional_coverage_and_unavailable_frequency(self):
        payload, raw = self.sample()
        result = inspect_payload("sample.json", payload, raw, "sample", 2)
        self.assertEqual(result["record_count"], 2)
        self.assertEqual(result["missing"]["phonetic"]["count"], 1)
        self.assertEqual(result["missing"]["pos"]["count"], 1)
        self.assertEqual(result["missing"]["example"]["count"], 2)
        self.assertEqual(result["frequency"]["negative_sentinel_count"], 1)
        self.assertEqual(result["structural_errors"], [])
        self.assertEqual(result["sha256"], hashlib.sha256(raw).hexdigest())

    def test_casefold_duplicates_and_word_whitespace_are_reported(self):
        payload, raw = self.sample()
        payload["words"].append({
            "word": " Alpha ",
            "meaning": "n. 甲",
            "phonetic": "",
            "pos": "",
            "example": "",
            "example_zh": "",
            "corpus_frequency": -1,
            "frequency_rank": "",
            "importance": 1,
        })
        result = inspect_payload("sample.json", payload, raw, "sample", 3)
        self.assertEqual(len(result["casefold_duplicates"]), 1)
        self.assertEqual(len(result["word_whitespace_issues"]), 1)

    def test_duplicate_meaning_segments_and_double_spaces_are_detected(self):
        payload, raw = self.sample()
        result = inspect_payload("sample.json", payload, raw, "sample", 2)
        self.assertEqual(result["duplicate_meaning_segments"]["count"], 1)
        self.assertEqual(result["multiple_space_meanings"]["count"], 1)

    def test_wrong_types_are_reported_not_silently_coerced(self):
        payload, raw = self.sample()
        payload["words"][0]["meaning"] = 123
        result = inspect_payload("sample.json", payload, raw, "sample", 2)
        self.assertTrue(any(
            item.get("field") == "meaning" and item.get("type") == "int"
            for item in result["field_type_errors"]
        ))

    def test_normal_line_breaks_are_not_control_character_errors(self):
        payload, raw = self.sample()
        payload["words"][0]["meaning"] = "n. 甲；\nadj. 第一的"
        result = inspect_payload("sample.json", payload, raw, "sample", 2)
        self.assertEqual(result["control_character_issues"], [])
        self.assertEqual(result["multiline_meanings"], 1)

    def test_invalid_top_level_or_words_shape_raises(self):
        with self.assertRaises(ValueError):
            inspect_payload("bad.json", [], b"[]")
        with self.assertRaises(ValueError):
            inspect_payload("bad.json", {"id": "bad", "words": {}}, b"{}")


if __name__ == "__main__":
    unittest.main()
