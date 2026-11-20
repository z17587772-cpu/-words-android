#!/usr/bin/env python3
"""Fill missing wordbook POS labels using pinned ECDICT data and local TSV sources.

Only blank POS fields are changed. A compact sidecar records checked lookups, so
the 66 MB ECDICT CSV is fetched only when a new uncached headword appears.
"""
from __future__ import annotations

import csv
import io
import json
import re
import sys
import unicodedata
import urllib.request
from pathlib import Path

ECDICT_COMMIT = "39cfb9672c0906b63569e0453c2e6abfb9ab182a"
ECDICT_CSV_URL = (
    "https://raw.githubusercontent.com/skywind3000/ECDICT/"
    f"{ECDICT_COMMIT}/ecdict.csv"
)
ECDICT_LICENSE_URL = (
    "https://raw.githubusercontent.com/skywind3000/ECDICT/"
    f"{ECDICT_COMMIT}/LICENSE"
)

ECDICT_POS = {
    "n": "n.", "v": "v.", "j": "adj.", "r": "adv.",
    "i": "prep.", "c": "conj.", "p": "pron.", "d": "det.",
    "m": "num.", "u": "int.", "x": "neg.",
}
INLINE_POS = re.compile(
    r"(?i)(?<![A-Za-z])((?:vt|vi|adj|adv|prep|conj|pron|num|art|aux|int|abbr|pl|n|v))\.(?=\s|[/&,]|$)"
)
SOURCE_POS = re.compile(
    r"(?i)(?<![A-Za-z])((?:vt|vi|adj|adv|prep|conj|pron|num|art|aux|int|abbr|pl|n|v))\.(?=\s|[/&,]|$)"
)


def normalize(value: object) -> str:
    return re.sub(
        r"\s+",
        " ",
        unicodedata.normalize("NFKC", str(value or "")).strip().casefold(),
    )


def canonical_inline(code: str) -> str:
    token = code.casefold()
    aliases = {
        "n": "n.", "v": "v.", "vt": "v.", "vi": "v.",
        "adj": "adj.", "adv": "adv.", "prep": "prep.",
        "conj": "conj.", "pron": "pron.", "num": "num.",
        "art": "art.", "aux": "aux.", "int": "int.",
        "abbr": "abbr.", "pl": "pl.",
    }
    return aliases.get(token, "")


def unique_tags(values: list[str]) -> list[str]:
    seen = set()
    result = []
    for value in values:
        if value and value not in seen:
            seen.add(value)
            result.append(value)
    return result


def inline_tags(meaning: str) -> list[str]:
    values = [canonical_inline(m.group(1)) for m in INLINE_POS.finditer(meaning or "")]
    return unique_tags(values)


def ecdict_tags(raw_pos: str, word: str) -> list[str]:
    weighted = []
    for part in (raw_pos or "").split("/"):
        code, _, raw_weight = part.strip().partition(":")
        code = code.strip().lower()
        if code == "a":
            # ECDICT treats its legacy "a" tag context-sensitively.
            if word in {"a", "the"}:
                label = "art."
            elif word in {"no", "every"}:
                label = "adj."
            else:
                label = "pron."
        else:
            label = ECDICT_POS.get(code, "")
        if not label:
            continue
        try:
            weight = float(raw_weight.strip()) if raw_weight.strip() else 0.0
        except ValueError:
            weight = 0.0
        weighted.append((weight, label))

    weighted.sort(key=lambda item: item[0], reverse=True)
    return unique_tags([label for _, label in weighted])


def load_school_tsv_pos(source_dir: Path) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    for path in sorted(source_dir.glob("*.tsv")):
        if path.name == "ecdict-pos.tsv":
            continue
        with path.open("r", encoding="utf-8-sig", newline="") as stream:
            for row in stream:
                fields = row.rstrip("\r\n").split("\t", 1)
                if len(fields) != 2:
                    continue
                word, meaning = fields
                key = normalize(word)
                tags = [canonical_inline(m.group(1)) for m in SOURCE_POS.finditer(meaning)]
                if key and tags:
                    result[key] = unique_tags(result.get(key, []) + tags)
    return result


def load_sidecar(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    if not path.exists():
        return result
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        word, separator, pos = line.partition("\t")
        if separator:
            result[normalize(word)] = pos.strip()
    return result


def fetch_ecdict(targets: set[str]) -> dict[str, list[str]]:
    print(f"Downloading pinned ECDICT POS source {ECDICT_COMMIT} for {len(targets):,} uncached words...")
    request = urllib.request.Request(
        ECDICT_CSV_URL,
        headers={"User-Agent": "words-android-pos-enrichment/1.0"},
    )
    result: dict[str, list[str]] = {}
    with urllib.request.urlopen(request, timeout=180) as response:
        text_stream = io.TextIOWrapper(response, encoding="utf-8-sig", newline="")
        reader = csv.DictReader(text_stream)
        if not reader.fieldnames or "word" not in reader.fieldnames or "pos" not in reader.fieldnames:
            raise RuntimeError("ECDICT CSV schema is missing word/pos columns")
        for row in reader:
            key = normalize(row.get("word"))
            if key not in targets:
                continue
            tags = ecdict_tags(row.get("pos", ""), key)
            if tags:
                result[key] = unique_tags(result.get(key, []) + tags)
    return result


def download_license(path: Path) -> None:
    if path.exists():
        return
    request = urllib.request.Request(
        ECDICT_LICENSE_URL,
        headers={"User-Agent": "words-android-pos-enrichment/1.0"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        path.write_bytes(response.read())


def main() -> None:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path("wordbooks").resolve()
    source_dir = root / "source"
    sidecar_path = source_dir / "ecdict-pos.tsv"
    license_path = root / "ECDICT-LICENSE-MIT.txt"

    packs = sorted(root.glob("*.json"))
    if not packs:
        raise RuntimeError(f"No wordbooks found under {root}")

    parsed = [(path, json.loads(path.read_text(encoding="utf-8"))) for path in packs]
    blank_words: dict[str, str] = {}
    word_meanings: dict[str, str] = {}
    for _, pack in parsed:
        for word in pack.get("words", []):
            key = normalize(word.get("word"))
            if key:
                word_meanings.setdefault(key, word.get("meaning", ""))
                if not str(word.get("pos", "")).strip():
                    blank_words[key] = word.get("word", key)

    source_pos = load_school_tsv_pos(source_dir)
    sidecar = load_sidecar(sidecar_path)
    uncached = {
        key for key in blank_words
        if key not in sidecar
        and not inline_tags(word_meanings.get(key, ""))
        and not source_pos.get(key)
    }
    ecdict_pos = fetch_ecdict(uncached) if uncached else {}
    for key in uncached:
        sidecar[key] = "/".join(ecdict_pos.get(key, [])) if ecdict_pos.get(key) else "-"

    filled = 0
    still_blank = 0
    for path, pack in parsed:
        for word in pack.get("words", []):
            if str(word.get("pos", "")).strip():
                continue
            key = normalize(word.get("word"))
            tags = inline_tags(word.get("meaning", ""))
            cached = sidecar.get(key, "")
            if cached and cached != "-":
                tags = unique_tags(tags + [part for part in cached.split("/") if part])
            tags = unique_tags(tags + source_pos.get(key, []))
            if tags:
                word["pos"] = "/".join(tags)
                filled += 1
            else:
                still_blank += 1

        # Bump the built-in pack schema version. Reviews are in a separate table.
        pack["version"] = max(3, int(pack.get("version", 1)))
        path.write_text(json.dumps(pack, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    source_dir.mkdir(parents=True, exist_ok=True)
    with sidecar_path.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write("# word\tpos; '-' means checked in the pinned ECDICT release but no POS data was available\n")
        for key, value in sorted(sidecar.items()):
            stream.write(f"{key}\t{value or '-'}\n")

    download_license(license_path)
    print(
        f"POS enrichment complete: {filled:,} entries filled; "
        f"{still_blank:,} entries still lack a reliable POS label."
    )


if __name__ == "__main__":
    main()
