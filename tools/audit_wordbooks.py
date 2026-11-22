#!/usr/bin/env python3
"""Deterministic, read-only quality audit for the bundled vocabulary source packs.

This tool does not mutate wordbook data. It writes a machine-readable report and
a human-readable summary, or verifies the committed reports with --check.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

AUDIT_VERSION = 1
EXPECTED_PACKS: dict[str, tuple[str, int]] = {
    "gaokao_standard_2025.json": ("gaokao_standard_2025", 3097),
    "highschool_extension_3743.json": ("highschool_extension_3743", 3743),
    "cet4_core.json": ("cet4_core", 3815),
    "cet6_core.json": ("cet6_core", 5371),
    "ielts_core.json": ("ielts_core", 4974),
    "toefl_core.json": ("toefl_core", 6959),
    "gre_core.json": ("gre_core", 7485),
    "kaoyan_core.json": ("kaoyan_core", 4787),
    "sat_core.json": ("sat_core", 4471),
    "gmat_core.json": ("gmat_core", 2996),
    "ngsl_nawl_core_3000.json": ("ngsl_nawl_core_3000", 3000),
}
LICENSE_FILES = (
    "SOURCES.md",
    "ECDICT-LICENSE-MIT.txt",
    "WordTyper-LICENSE-MIT.txt",
    "WordTyper-NOTICE.md",
    "KyleBing-LICENSE.txt",
    "NGSL-ATTRIBUTION.txt",
)


def is_blank(value: Any) -> bool:
    return not isinstance(value, str) or not value.strip()


def normalize_headword(value: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", value).strip()).casefold()


def _duplicate_segments(meaning: str) -> list[str]:
    segments = [
        re.sub(r"\s+", " ", part).strip().casefold()
        for part in re.split(r"[；;]", meaning)
    ]
    counts = Counter(segment for segment in segments if segment)
    return sorted(segment for segment, count in counts.items() if count > 1)


def inspect_payload(
    filename: str,
    payload: Any,
    raw_bytes: bytes,
    expected_id: str | None = None,
    expected_count: int | None = None,
) -> dict[str, Any]:
    errors: list[str] = []
    if not isinstance(payload, dict):
        raise ValueError(f"{filename}: top-level JSON value must be an object")
    words = payload.get("words")
    if not isinstance(words, list):
        raise ValueError(f"{filename}: 'words' must be an array")

    pack_id = payload.get("id")
    if expected_id and pack_id != expected_id:
        errors.append(f"pack id mismatch: expected {expected_id}, got {pack_id!r}")
    if expected_count is not None and len(words) != expected_count:
        errors.append(f"record count mismatch: expected {expected_count}, got {len(words)}")

    exact_seen: dict[str, int] = {}
    folded_seen: dict[str, tuple[str, int]] = {}
    missing: dict[str, list[str]] = {
        field: [] for field in ("word", "meaning", "phonetic", "pos", "example", "example_zh")
    }
    type_errors: list[dict[str, Any]] = []
    exact_duplicates: list[dict[str, Any]] = []
    folded_duplicates: list[dict[str, Any]] = []
    trim_issues: list[dict[str, Any]] = []
    control_issues: list[dict[str, Any]] = []
    placeholder_meanings: list[str] = []
    duplicate_meaning_segments: list[dict[str, Any]] = []
    multi_space_meanings: list[str] = []
    phonetic_not_slash_delimited: list[str] = []
    phonetic_symbols: Counter[str] = Counter()
    pos_values: Counter[str] = Counter()
    frequency_negative = 0
    frequency_missing = 0
    rank_missing = 0
    importance_missing = 0
    multiline_meanings = 0
    tagged_meanings = 0

    for index, row in enumerate(words):
        if not isinstance(row, dict):
            type_errors.append({"index": index, "field": "row", "type": type(row).__name__})
            continue
        word_value = row.get("word")
        meaning_value = row.get("meaning")
        for field in ("word", "meaning", "phonetic", "pos", "example", "example_zh"):
            value = row.get(field)
            if is_blank(value):
                missing[field].append(word_value if isinstance(word_value, str) else f"#{index}")
        for field in ("word", "meaning", "phonetic", "pos", "example", "example_zh"):
            value = row.get(field)
            if value is not None and not isinstance(value, str):
                type_errors.append({"index": index, "word": word_value, "field": field, "type": type(value).__name__})
        if not isinstance(word_value, str) or not word_value.strip():
            continue

        if word_value != word_value.strip():
            trim_issues.append({"index": index, "word": word_value})
        if any((unicodedata.category(char) == "Cc" and char not in "\t\n\r") or char == "\ufffd" for char in word_value + str(meaning_value or "") + str(row.get("phonetic") or "")):
            control_issues.append({"index": index, "word": word_value})

        if word_value in exact_seen:
            exact_duplicates.append({"word": word_value, "first_index": exact_seen[word_value], "duplicate_index": index})
        else:
            exact_seen[word_value] = index

        folded = normalize_headword(word_value)
        if folded in folded_seen:
            previous_word, previous_index = folded_seen[folded]
            folded_duplicates.append({
                "normalized": folded,
                "first_word": previous_word,
                "first_index": previous_index,
                "duplicate_word": word_value,
                "duplicate_index": index,
            })
        else:
            folded_seen[folded] = (word_value, index)

        pos = row.get("pos")
        if isinstance(pos, str) and pos.strip():
            pos_values[pos.strip()] += 1

        phonetic = row.get("phonetic")
        if isinstance(phonetic, str) and phonetic.strip():
            for char in phonetic:
                if ord(char) > 127:
                    phonetic_symbols[char] += 1
            if not (phonetic.startswith("/") and phonetic.endswith("/")):
                phonetic_not_slash_delimited.append(word_value)

        if isinstance(meaning_value, str):
            if "\n" in meaning_value or "\r" in meaning_value:
                multiline_meanings += 1
            if re.search(r" {2,}", meaning_value):
                multi_space_meanings.append(word_value)
            if re.search(r"<[^>]+>", meaning_value):
                tagged_meanings += 1
            if re.fullmatch(r"(?i)\s*(?:-|n/?a|none|null|undefined|暂无|待补充)\s*", meaning_value):
                placeholder_meanings.append(word_value)
            duplicate_segments = _duplicate_segments(meaning_value)
            if duplicate_segments:
                duplicate_meaning_segments.append({
                    "word": word_value,
                    "duplicated_segments": duplicate_segments,
                })

        frequency = row.get("corpus_frequency")
        if frequency is None or not isinstance(frequency, (int, float)) or isinstance(frequency, bool):
            frequency_missing += 1
        elif frequency < 0:
            frequency_negative += 1
        if is_blank(row.get("frequency_rank")):
            rank_missing += 1
        importance = row.get("importance")
        if importance is None or not isinstance(importance, (int, float)) or isinstance(importance, bool):
            importance_missing += 1

    count = len(words)
    return {
        "filename": filename,
        "id": pack_id,
        "display_name": payload.get("name", ""),
        "version": payload.get("version"),
        "record_count": count,
        "sha256": hashlib.sha256(raw_bytes).hexdigest(),
        "source_bytes": len(raw_bytes),
        "unique_exact_headwords": len(exact_seen),
        "unique_casefolded_headwords": len(folded_seen),
        "missing": {field: {"count": len(values), "samples": values[:50]} for field, values in missing.items()},
        "field_type_errors": type_errors,
        "exact_duplicates": exact_duplicates,
        "casefold_duplicates": folded_duplicates,
        "word_whitespace_issues": trim_issues,
        "control_character_issues": control_issues,
        "placeholder_meanings": placeholder_meanings,
        "duplicate_meaning_segments": {
            "count": len(duplicate_meaning_segments),
            "samples": duplicate_meaning_segments[:30],
        },
        "multiple_space_meanings": {
            "count": len(multi_space_meanings),
            "samples": multi_space_meanings[:30],
        },
        "multiline_meanings": multiline_meanings,
        "tagged_meanings": tagged_meanings,
        "phonetic_not_slash_delimited": phonetic_not_slash_delimited,
        "phonetic_non_ascii_symbols": [
            {"character": char, "codepoint": f"U+{ord(char):04X}", "occurrences": number}
            for char, number in sorted(phonetic_symbols.items(), key=lambda pair: (-pair[1], pair[0]))
        ],
        "pos_value_counts": [
            {"value": value, "count": number}
            for value, number in pos_values.most_common(40)
        ],
        "frequency": {
            "negative_sentinel_count": frequency_negative,
            "missing_or_non_numeric_count": frequency_missing,
            "rank_missing_count": rank_missing,
        },
        "importance_missing_count": importance_missing,
        "structural_errors": errors,
    }


def build_report(root: Path) -> dict[str, Any]:
    wordbooks_dir = root / "wordbooks"
    if not wordbooks_dir.is_dir():
        raise FileNotFoundError(f"wordbooks directory not found: {wordbooks_dir}")

    pack_results = []
    global_words: dict[str, dict[str, set[str]]] = defaultdict(lambda: {"forms": set(), "packs": set()})
    total_records = 0
    total_missing_examples = 0
    total_missing_example_zh = 0
    global_errors: list[str] = []

    for filename, (expected_id, expected_count) in EXPECTED_PACKS.items():
        path = wordbooks_dir / filename
        if not path.is_file():
            global_errors.append(f"missing pack file: {filename}")
            continue
        raw_bytes = path.read_bytes()
        try:
            payload = json.loads(raw_bytes.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            global_errors.append(f"{filename}: invalid UTF-8 JSON: {error}")
            continue
        pack = inspect_payload(filename, payload, raw_bytes, expected_id, expected_count)
        pack_results.append(pack)
        total_records += pack["record_count"]
        total_missing_examples += pack["missing"]["example"]["count"]
        total_missing_example_zh += pack["missing"]["example_zh"]["count"]
        if pack["structural_errors"]:
            global_errors.extend(f"{filename}: {message}" for message in pack["structural_errors"])
        if pack["field_type_errors"]:
            global_errors.append(f"{filename}: {len(pack['field_type_errors'])} field type errors")
        if pack["exact_duplicates"]:
            global_errors.append(f"{filename}: {len(pack['exact_duplicates'])} exact duplicate headwords")
        if pack["casefold_duplicates"]:
            global_errors.append(f"{filename}: {len(pack['casefold_duplicates'])} casefold duplicate headwords")

        for row in payload["words"]:
            if not isinstance(row, dict) or not isinstance(row.get("word"), str) or not row["word"].strip():
                continue
            key = normalize_headword(row["word"])
            global_words[key]["forms"].add(row["word"])
            global_words[key]["packs"].add(filename)

    if set(path.name for path in wordbooks_dir.glob("*.json")) != set(EXPECTED_PACKS):
        actual = sorted(path.name for path in wordbooks_dir.glob("*.json"))
        expected = sorted(EXPECTED_PACKS)
        global_errors.append(f"wordbook JSON inventory mismatch: expected {expected}, found {actual}")

    shared = [
        {"headword": key, "packs": sorted(value["packs"])}
        for key, value in global_words.items()
        if len(value["packs"]) > 1
    ]
    case_variants = [
        {"normalized": key, "forms": sorted(value["forms"]), "packs": sorted(value["packs"])}
        for key, value in global_words.items()
        if len(value["forms"]) > 1
    ]

    license_files = [
        {"path": name, "present": (wordbooks_dir / name).is_file()}
        for name in LICENSE_FILES
    ]
    missing_license_files = [item["path"] for item in license_files if not item["present"]]
    if missing_license_files:
        global_errors.append(f"missing source/license attribution files: {missing_license_files}")

    return {
        "audit_version": AUDIT_VERSION,
        "scope": "read-only audit of the 11 source JSON packs; no data mutation",
        "total_pack_records": total_records,
        "global_unique_headwords_casefolded": len(global_words),
        "headwords_shared_by_multiple_packs": len(shared),
        "repeated_occurrences_across_packs": total_records - len(global_words),
        "cross_pack_case_variants": case_variants,
        "cross_pack_case_variant_count": len(case_variants),
        "missing_examples_total": total_missing_examples,
        "missing_example_translations_total": total_missing_example_zh,
        "examples_policy": (
            "All examples and example translations are blank in the current packs. "
            "wordbooks/SOURCES.md states that missing examples are intentionally left blank. "
            "This is not a JSON integrity error, but remains a learning-content coverage gap."
        ),
        "source_license_files": license_files,
        "global_errors": global_errors,
        "packs": pack_results,
        "interpretation": {
            "cross_pack_overlap": "Expected for overlapping curriculum and exam reference lists; do not merge or delete automatically.",
            "missing_phonetic_pos": "Reported as coverage gaps, not automatic data corruption; enrich only with traceable, compatible sources.",
            "phonetic_symbols": "Non-ASCII pronunciation symbols are inventoried for source-level review; the audit does not rewrite IPA or infer that every symbol is invalid.",
            "meaning_segments": "Repeated segments and repeated spaces are review candidates; preserve different senses and grammatical labels unless manually verified.",
            "frequency_negative": "Negative corpus_frequency is a deliberate unavailable-data sentinel, not a frequency of zero.",
        },
    }


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Wordbook data-quality audit",
        "",
        "> Deterministic, read-only report generated by `tools/audit_wordbooks.py`. No wordbook source data was changed.",
        "",
        "## Overall",
        "",
        f"- Source records across 11 packs: **{report['total_pack_records']:,}**",
        f"- Case-insensitive unique headwords across all packs: **{report['global_unique_headwords_casefolded']:,}**",
        f"- Headwords shared by multiple packs: **{report['headwords_shared_by_multiple_packs']:,}** (expected overlap)",
        f"- Repeated cross-pack occurrences: **{report['repeated_occurrences_across_packs']:,}**",
        f"- Cross-pack case variants requiring review: **{report['cross_pack_case_variant_count']}**",
        f"- Blank examples / translations: **{report['missing_examples_total']:,} / {report['missing_example_translations_total']:,}**",
        "",
        report["examples_policy"],
        "",
        "## Per-pack coverage",
        "",
        "| Pack | Records | Missing phonetic | Missing POS | Missing example | Duplicate senses | Multi-space meanings | SHA-256 (prefix) |",
        "|---|---:|---:|---:|---:|---:|---:|---|",
    ]
    for pack in report["packs"]:
        lines.append(
            f"| {pack['filename']} | {pack['record_count']:,} | "
            f"{pack['missing']['phonetic']['count']:,} | {pack['missing']['pos']['count']:,} | "
            f"{pack['missing']['example']['count']:,} | "
            f"{pack['duplicate_meaning_segments']['count']:,} | "
            f"{pack['multiple_space_meanings']['count']:,} | "
            f"`{pack['sha256'][:12]}` |"
        )
    lines += ["", "## Findings", ""]
    for pack in report["packs"]:
        lines += [f"### {pack['filename']}", ""]
        lines.append(
            f"- **Phonetic coverage:** {pack['record_count'] - pack['missing']['phonetic']['count']:,}/"
            f"{pack['record_count']:,}; missing headwords: "
            + (", ".join(pack["missing"]["phonetic"]["samples"][:30]) or "none")
            + (" …" if pack["missing"]["phonetic"]["count"] > 30 else "")
        )
        lines.append(
            f"- **POS coverage:** {pack['record_count'] - pack['missing']['pos']['count']:,}/"
            f"{pack['record_count']:,}; missing headwords: "
            + (", ".join(pack["missing"]["pos"]["samples"][:30]) or "none")
            + (" …" if pack["missing"]["pos"]["count"] > 30 else "")
        )
        lines.append(
            f"- **Meaning review candidates:** {pack['duplicate_meaning_segments']['count']} words contain repeated semicolon-separated segments; "
            f"{pack['multiple_space_meanings']['count']} contain consecutive spaces."
        )
        symbols = pack["phonetic_non_ascii_symbols"][:10]
        if symbols:
            lines.append(
                "- **Pronunciation symbol inventory:** "
                + ", ".join(f"{item['character']} ({item['codepoint']}, {item['occurrences']:,})" for item in symbols)
                + ". Source-level notation review required; no automatic replacement."
            )
        lines.append(
            f"- **Frequency:** {pack['frequency']['negative_sentinel_count']:,} rows use the documented unavailable sentinel; "
            f"{pack['frequency']['rank_missing_count']:,} ranks are blank."
        )
        if pack["structural_errors"] or pack["field_type_errors"] or pack["exact_duplicates"] or pack["casefold_duplicates"] or pack["control_character_issues"] or pack["word_whitespace_issues"] or pack["placeholder_meanings"]:
            lines.append("- **Structural/data-integrity problems:**")
            for value in pack["structural_errors"]:
                lines.append(f"  - {value}")
            for field in ("field_type_errors", "exact_duplicates", "casefold_duplicates", "control_character_issues", "word_whitespace_issues", "placeholder_meanings"):
                if pack[field]:
                    lines.append(f"  - {field}: {len(pack[field])}")
        lines.append("")
    lines += [
        "## Cross-pack case variants",
        "",
        "These are not automatically duplicates to delete. Capitalization can distinguish proper nouns, the pronoun I, abbreviations, and ordinary forms.",
        "",
        "| Normalized key | Forms | Packs |",
        "|---|---|---|",
    ]
    for item in report["cross_pack_case_variants"]:
        lines.append(f"| {item['normalized']} | {', '.join(item['forms'])} | {', '.join(item['packs'])} |")
    lines += [
        "",
        "## Source and license inventory",
        "",
        "| File | Present |",
        "|---|---|",
    ]
    for item in report["source_license_files"]:
        lines.append(f"| {item['path']} | {'yes' if item['present'] else 'NO'} |")
    lines += [
        "",
        "## Review policy",
        "",
        "- Do not delete words merely because they occur in multiple packs.",
        "- Do not fill phonetics/POS from an unpinned or unlicensed source.",
        "- Do not fabricate examples; select only a traceable source with compatible redistribution rights.",
        "- Keep curriculum importance separate from third-party corpus frequency.",
        "- Treat this automated report as a triage tool, not a substitute for semantic proofreading.",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output-dir", type=Path, default=None)
    parser.add_argument("--write", action="store_true", help="write the deterministic JSON and Markdown reports")
    parser.add_argument("--check", action="store_true", help="fail if committed reports differ from current source data")
    args = parser.parse_args()

    root = args.root.resolve()
    output_dir = (args.output_dir or root / "wordbooks" / "audit").resolve()
    report = build_report(root)
    json_text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    markdown_text = render_markdown(report)
    json_path = output_dir / "quality-report.json"
    markdown_path = output_dir / "quality-report.md"

    if args.write:
        output_dir.mkdir(parents=True, exist_ok=True)
        json_path.write_text(json_text, encoding="utf-8")
        markdown_path.write_text(markdown_text, encoding="utf-8")
        print(f"Wrote {json_path} and {markdown_path}")
    if args.check:
        missing = [str(path) for path in (json_path, markdown_path) if not path.is_file()]
        if missing:
            raise SystemExit(f"Committed audit report missing: {', '.join(missing)}")
        if json_path.read_text(encoding="utf-8") != json_text:
            raise SystemExit(f"{json_path} is stale; run tools/audit_wordbooks.py --write and commit the updated report")
        if markdown_path.read_text(encoding="utf-8") != markdown_text:
            raise SystemExit(f"{markdown_path} is stale; run tools/audit_wordbooks.py --write and commit the updated report")
        print("Wordbook audit report matches all source packs.")
    if report["global_errors"]:
        for error in report["global_errors"]:
            print(f"ERROR: {error}")
        return 1
    print(
        f"Audited {len(report['packs'])}/{len(EXPECTED_PACKS)} packs, "
        f"{report['total_pack_records']:,} records, "
        f"{report['global_unique_headwords_casefolded']:,} case-insensitive unique headwords."
    )
    if not (args.write or args.check):
        print("Use --write to persist reports or --check to verify committed reports.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
