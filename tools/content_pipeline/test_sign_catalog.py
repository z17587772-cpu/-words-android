import base64
import json
import unittest

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization

from sign_catalog import canonical_payload, sign_payload


class CatalogSigningTests(unittest.TestCase):
    def setUp(self):
        self.catalog = {
            "schema_version": 1,
            "catalog_version": 7,
            "packages": [{
                "package_id": "advanced_week_001",
                "package_version": 1,
                "minimum_app_version_code": 22,
                "url": "https://cdn.example.org/listening/advanced_week_001.zip",
                "size_bytes": 1024,
                "sha256": "a" * 64,
            }],
        }
        self.key = Ed25519PrivateKey.generate()

    def test_signed_payload_verifies_with_pinned_public_key(self):
        payload = canonical_payload(self.catalog)
        envelope = sign_payload(payload, self.key)
        signature = base64.b64decode(envelope["signature"])
        public_key = self.key.public_key()
        public_key.verify(signature, base64.b64decode(envelope["payload"]))

    def test_canonical_payload_is_stable(self):
        first = canonical_payload(self.catalog)
        reordered = json.loads(json.dumps(self.catalog))
        reordered["packages"][0] = dict(reversed(list(reordered["packages"][0].items())))
        self.assertEqual(first, canonical_payload(reordered))

    def test_duplicate_package_ids_are_rejected(self):
        self.catalog["packages"].append(dict(self.catalog["packages"][0], package_version=2))
        with self.assertRaisesRegex(ValueError, "duplicate package_id"):
            canonical_payload(self.catalog)

    def test_non_https_package_url_is_rejected(self):
        self.catalog["packages"][0]["url"] = "http://cdn.example.org/course.zip"
        with self.assertRaisesRegex(ValueError, "HTTPS"):
            canonical_payload(self.catalog)

    def test_invalid_digest_is_rejected(self):
        self.catalog["packages"][0]["sha256"] = "not-a-digest"
        with self.assertRaisesRegex(ValueError, "sha256"):
            canonical_payload(self.catalog)


if __name__ == "__main__":
    unittest.main()
