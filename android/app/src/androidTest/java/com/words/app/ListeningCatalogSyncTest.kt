package com.words.app

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.Signature

@RunWith(AndroidJUnit4::class)
class ListeningCatalogSyncTest {
    @Test
    fun verifiesEd25519EnvelopeAndRejectsPayloadTampering() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val payload = JSONObject()
            .put("schema_version", 1)
            .put("catalog_version", 1)
            .put("packages", JSONArray())
            .toString()
            .toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(pair.private)
        signer.update(payload)
        val envelope = JSONObject()
            .put("payload", Base64.encodeToString(payload, Base64.NO_WRAP))
            .put("signature", Base64.encodeToString(signer.sign(), Base64.NO_WRAP))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val publicKey = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)

        assertArrayEquals(payload, ListeningCatalogSync.verifyEnvelope(envelope, publicKey))

        val tamperedPayload = payload.copyOf().also { it[it.lastIndex - 2] = '2'.code.toByte() }
        val tamperedEnvelope = JSONObject()
            .put("payload", Base64.encodeToString(tamperedPayload, Base64.NO_WRAP))
            .put("signature", JSONObject(String(envelope)).getString("signature"))
            .toString()
            .toByteArray(Charsets.UTF_8)
        try {
            ListeningCatalogSync.verifyEnvelope(tamperedEnvelope, publicKey)
            throw AssertionError("Tampered catalog payload was accepted")
        } catch (expected: IllegalArgumentException) {
            // Signature mismatch must fail closed.
        }
    }

    @Test
    fun parsesOnlySameHostHttpsPackages() {
        val catalog = JSONObject()
            .put("schema_version", 1)
            .put("catalog_version", 3)
            .put("packages", JSONArray().put(packageEntry("https://cdn.example.org/course.zip")))
        val (version, entries) = ListeningCatalogSync.parseCatalog(
            catalog.toString().toByteArray(Charsets.UTF_8),
            "cdn.example.org",
            22
        )
        assertEquals(3L, version)
        assertEquals("advanced_week_001", entries.single().id)

        val foreignHost = JSONObject()
            .put("schema_version", 1)
            .put("catalog_version", 4)
            .put("packages", JSONArray().put(packageEntry("https://other.example.org/course.zip")))
        try {
            ListeningCatalogSync.parseCatalog(
                foreignHost.toString().toByteArray(Charsets.UTF_8),
                "cdn.example.org",
                22
            )
            throw AssertionError("Foreign package host was accepted")
        } catch (expected: IllegalArgumentException) {
            // The catalog host is the package download trust boundary.
        }
    }

    @Test
    fun rejectsDuplicatePackageIdsAndSkipsPackagesRequiringANewerApp() {
        val duplicate = JSONObject()
            .put("schema_version", 1)
            .put("catalog_version", 5)
            .put("packages", JSONArray()
                .put(packageEntry("https://cdn.example.org/one.zip"))
                .put(packageEntry("https://cdn.example.org/two.zip")))
        try {
            ListeningCatalogSync.parseCatalog(
                duplicate.toString().toByteArray(Charsets.UTF_8),
                "cdn.example.org",
                22
            )
            throw AssertionError("Duplicate package IDs were accepted")
        } catch (expected: IllegalArgumentException) {
            // A catalog may publish only one current version per package ID.
        }

        val newerApp = JSONObject()
            .put("schema_version", 1)
            .put("catalog_version", 6)
            .put("packages", JSONArray().put(packageEntry(
                "https://cdn.example.org/course.zip",
                minimumAppVersion = 23
            )))
        try {
            val (_, compatibleEntries) = ListeningCatalogSync.parseCatalog(
                newerApp.toString().toByteArray(Charsets.UTF_8),
                "cdn.example.org",
                22
            )
            assertEquals(0, compatibleEntries.size)
        }
    }

    private fun packageEntry(url: String, minimumAppVersion: Int = 22): JSONObject =
        JSONObject()
            .put("package_id", "advanced_week_001")
            .put("package_version", 1)
            .put("minimum_app_version_code", minimumAppVersion)
            .put("url", url)
            .put("size_bytes", 1024)
            .put("sha256", "a".repeat(64))
}
