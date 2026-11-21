#!/usr/bin/env python3
"""Sign a validated listening catalog payload with an Ed25519 publisher key.

The private key is supplied at runtime through LISTENING_CATALOG_PRIVATE_KEY_FILE.
It must never be committed to this repository or packaged into the Android app.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import tempfile
from pathlib import Path
from urllib.parse import urlparse

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

MAX_ARCHIVE_BYTES = 1_100_000_000
PACKAGE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{1,79}")
SHA256 = re.compile(r"[a-fA-F0-9]{64}")


def canonical_payload(catalog: dict) -> bytes:
    if catalog.get("schema_version") != 1:
        raise ValueError("Unsupported catalog schema_version")
    version = catalog.get("catalog_version")
    if not isinstance(version, int) or version <= 0:
        raise ValueError("catalog_version must be a positive integer")
    packages = catalog.get("packages")
    if not isinstance(packages, list) or len(packages) > 500:
        raise ValueError("packages must be an array containing at most 500 entries")

    seen: set[str] = set()
    for index, item in enumerate(packages):
        if not isinstance(item, dict):
            raise ValueError(f"packages[{index}] must be an object")
        package_id = item.get("package_id")
        if not isinstance(package_id, str) or not PACKAGE_ID.fullmatch(package_id):
            raise ValueError(f"packages[{index}].package_id is invalid")
        if package_id in seen:
            raise ValueError(f"duplicate package_id: {package_id}")
        seen.add(package_id)
        for field in ("package_version", "minimum_app_version_code"):
            if not isinstance(item.get(field), int) or item[field] <= 0:
                raise ValueError(f"packages[{index}].{field} must be positive")
        size = item.get("size_bytes")
        if not isinstance(size, int) or not 1 <= size <= MAX_ARCHIVE_BYTES:
            raise ValueError(f"packages[{index}].size_bytes is invalid")
        digest = item.get("sha256")
        if not isinstance(digest, str) or not SHA256.fullmatch(digest):
            raise ValueError(f"packages[{index}].sha256 is invalid")
        parsed = urlparse(item.get("url", ""))
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError(f"packages[{index}].url must be an HTTPS URL without credentials")

    return json.dumps(
        catalog, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def sign_payload(payload: bytes, private_key: Ed25519PrivateKey) -> dict[str, str]:
    return {
        "payload": base64.b64encode(payload).decode("ascii"),
        "signature": base64.b64encode(private_key.sign(payload)).decode("ascii"),
    }


def load_private_key() -> Ed25519PrivateKey:
    key_file = os.environ.get("LISTENING_CATALOG_PRIVATE_KEY_FILE")
    if not key_file:
        raise RuntimeError("LISTENING_CATALOG_PRIVATE_KEY_FILE is required")
    key_bytes = Path(key_file).read_bytes()
    key = serialization.load_pem_private_key(key_bytes, password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise TypeError("Publisher key must be Ed25519")
    return key


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Unsigned catalog JSON")
    parser.add_argument("output", type=Path, help="Signed envelope JSON")
    args = parser.parse_args()
    catalog = json.loads(args.input.read_text(encoding="utf-8"))
    payload = canonical_payload(catalog)
    envelope = sign_payload(payload, load_private_key())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.write_text(
        json.dumps(envelope, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    temporary.replace(args.output)
    print(f"Signed catalog version {catalog['catalog_version']} with {len(catalog['packages'])} package entries.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
