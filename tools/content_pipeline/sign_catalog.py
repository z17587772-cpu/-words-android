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
import hashlib
from pathlib import Path
from urllib.parse import unquote, urlparse

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

MAX_ARCHIVE_BYTES = 90 * 1024 * 1024
PACKAGE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{1,79}")
SHA256 = re.compile(r"[a-fA-F0-9]{64}")


def canonical_payload(catalog: dict) -> bytes:
    if type(catalog.get("schema_version")) is not int or catalog.get("schema_version") != 1:
        raise ValueError("Unsupported catalog schema_version")
    version = catalog.get("catalog_version")
    if type(version) is not int or version <= 0:
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
            if type(item.get(field)) is not int or item[field] <= 0:
                raise ValueError(f"packages[{index}].{field} must be positive")
        size = item.get("size_bytes")
        if not isinstance(size, int) or not 1 <= size <= MAX_ARCHIVE_BYTES:
            raise ValueError(f"packages[{index}].size_bytes is invalid")
        digest = item.get("sha256")
        if not isinstance(digest, str) or not SHA256.fullmatch(digest):
            raise ValueError(f"packages[{index}].sha256 is invalid")
        url = item.get("url")
        if not isinstance(url, str):
            raise ValueError(f"packages[{index}].url must be a string")
        parsed = urlparse(url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError(f"packages[{index}].url must be an HTTPS URL without credentials")

    return json.dumps(
        catalog, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def validate_assets(catalog: dict, asset_root: Path) -> None:
    root = asset_root.resolve()
    for item in catalog["packages"]:
        parsed = urlparse(item["url"])
        prefix = "/z17587772-cpu/-words-android/content-inbox/"
        if parsed.hostname != "raw.githubusercontent.com" or not parsed.path.startswith(prefix):
            raise ValueError("Package URL must point to this repository's content-inbox branch")
        relative = unquote(parsed.path[len(prefix):])
        if not relative or relative.startswith("/") or ".." in Path(relative).parts:
            raise ValueError("Package URL contains an unsafe asset path")
        asset = (root / relative).resolve()
        if root not in asset.parents or not asset.is_file():
            raise ValueError(f"Package asset does not exist inside content-inbox: {relative}")
        if asset.stat().st_size != item["size_bytes"]:
            raise ValueError(f"Package size mismatch: {relative}")
        hasher = hashlib.sha256()
        with asset.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                hasher.update(chunk)
        digest = hasher.hexdigest()
        if digest.lower() != item["sha256"].lower():
            raise ValueError(f"Package SHA-256 mismatch: {relative}")


def previous_catalog_version(envelope_path: Path) -> int | None:
    if not envelope_path.is_file():
        return None
    envelope = json.loads(envelope_path.read_text(encoding="utf-8"))
    payload = base64.b64decode(envelope["payload"], validate=True)
    previous = json.loads(payload.decode("utf-8"))
    version = previous.get("catalog_version")
    if type(version) is not int or version <= 0:
        raise ValueError("Existing signed catalog has an invalid catalog_version")
    return version


def validate_version_progression(catalog: dict, previous_version: int | None) -> None:
    version = catalog.get("catalog_version")
    if type(version) is not int or version <= 0:
        raise ValueError("catalog_version must be a positive integer")
    if previous_version is not None and version <= previous_version:
        raise ValueError("catalog_version must increase beyond the previously published catalog")


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
    parser.add_argument("--asset-root", type=Path, help="Verify package ZIPs against files in content-inbox")
    parser.add_argument("--previous-envelope", type=Path, help="Reject a non-increasing catalog version")
    args = parser.parse_args()
    catalog = json.loads(args.input.read_text(encoding="utf-8"))
    if args.asset_root:
        validate_assets(catalog, args.asset_root)
    payload = canonical_payload(catalog)
    if args.previous_envelope:
        previous_version = previous_catalog_version(args.previous_envelope)
        validate_version_progression(catalog, previous_version)
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
