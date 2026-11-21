package com.words.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InternetArchiveMetadataPolicyTest {
    @Test
    fun allowsOnlyExplicitApprovedCreativeCommonsLicenses() {
        assertEquals(
            "CC0 1.0",
            InternetArchiveMetadataPolicy.normalizeLicense("https://creativecommons.org/publicdomain/zero/1.0/")?.second
        )
        assertEquals(
            "CC BY 4.0",
            InternetArchiveMetadataPolicy.normalizeLicense("https://creativecommons.org/licenses/by/4.0")?.second
        )
        assertEquals(
            "CC BY-SA 4.0",
            InternetArchiveMetadataPolicy.normalizeLicense("https://creativecommons.org/licenses/by-sa/4.0/")?.second
        )
        assertNull(InternetArchiveMetadataPolicy.normalizeLicense(""))
        assertNull(InternetArchiveMetadataPolicy.normalizeLicense("https://creativecommons.org/licenses/by-nc/4.0/"))
        assertNull(InternetArchiveMetadataPolicy.normalizeLicense("https://archive.org/details/public-audio"))
    }

    @Test
    fun rejectsClearlyAdultOrExplicitMetadata() {
        assertTrue(InternetArchiveMetadataPolicy.isAgeAppropriateAudio(
            "A public-domain story", "An English short story for learners", JSONArray().put("Education")
        ))
        assertTrue(!InternetArchiveMetadataPolicy.isAgeAppropriateAudio(
            "Erotica collection", "Audio stories", JSONArray()
        ))
        assertTrue(!InternetArchiveMetadataPolicy.isAgeAppropriateAudio(
            "A recording", "Explicit sexual content", JSONArray()
        ))
        assertTrue(!InternetArchiveMetadataPolicy.isAgeAppropriateAudio(
            "An item", "For adult-only audiences", JSONArray()
        ))
    }

    @Test
    fun recognizesEnglishLanguageStringsAndArraysOnly() {
        assertTrue(InternetArchiveMetadataPolicy.isEnglish("English"))
        assertTrue(InternetArchiveMetadataPolicy.isEnglish("eng"))
        assertTrue(InternetArchiveMetadataPolicy.isEnglish("en"))
        assertTrue(InternetArchiveMetadataPolicy.isEnglish(JSONArray().put("French").put("English")))
        assertTrue(!InternetArchiveMetadataPolicy.isEnglish("French"))
        assertTrue(!InternetArchiveMetadataPolicy.isEnglish(null))
    }
}
