package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun isNewer_tagWithHigherVersionCode_returnsTrue() {
        assertTrue(AppUpdater.isNewer("build-85-1-vc333", 332))
    }

    @Test
    fun isNewer_sameVersionCode_returnsFalse() {
        org.junit.Assert.assertFalse(AppUpdater.isNewer("build-85-1-vc332", 332))
    }

    @Test
    fun isNewer_lowerVersionCode_returnsFalse() {
        org.junit.Assert.assertFalse(AppUpdater.isNewer("build-84-1-vc331", 332))
    }

    @Test
    fun isNewer_tagWithoutVersionCode_returnsFalse() {
        // Older tags (no -vc suffix) are never offered as updates.
        org.junit.Assert.assertFalse(AppUpdater.isNewer("v2.4.5", 300))
        org.junit.Assert.assertFalse(AppUpdater.isNewer("build-84-1", 300))
    }

    @Test
    fun parseRelease_newBuildWithApk_parsesSuccessfully() {
        val json = """
            {
                "tag_name": "build-85-1-vc333",
                "name": "WoW Forever build 85",
                "body": "Bug fixes and improvements",
                "assets": [
                    {
                        "name": "WoW-Forever.apk",
                        "browser_download_url": "https://github.com/hyylanlian/wow-forever-android/releases/download/build-85-1-vc333/WoW-Forever.apk",
                        "size": 160000000
                    }
                ]
            }
        """.trimIndent()

        val release = AppUpdater.parseRelease(json, 332)

        assertNotNull(release)
        assertEquals("build-85-1-vc333", release?.version)
        assertEquals("WoW Forever build 85", release?.name)
        assertEquals("Bug fixes and improvements", release?.notes)
        assertEquals("https://github.com/hyylanlian/wow-forever-android/releases/download/build-85-1-vc333/WoW-Forever.apk", release?.downloadUrl)
        assertEquals(160000000L, release?.sizeBytes)
    }

    @Test
    fun parseRelease_sameOrOlderBuild_returnsNull() {
        val json = """
            {
                "tag_name": "build-84-1-vc332",
                "name": "WoW Forever build 84",
                "body": "Existing release",
                "assets": [
                    {
                        "name": "WoW-Forever.apk",
                        "browser_download_url": "https://github.com/hyylanlian/wow-forever-android/releases/download/build-84-1-vc332/WoW-Forever.apk",
                        "size": 160000000
                    }
                ]
            }
        """.trimIndent()

        assertNull(AppUpdater.parseRelease(json, 332))
    }

    @Test
    fun parseRelease_noApkAsset_returnsNull() {
        val json = """
            {
                "tag_name": "build-85-1-vc333",
                "name": "WoW Forever build 85",
                "body": "Source only release",
                "assets": [
                    {
                        "name": "source.tar.gz",
                        "browser_download_url": "https://example.com/source.tar.gz",
                        "size": 5000
                    }
                ]
            }
        """.trimIndent()

        assertNull(AppUpdater.parseRelease(json, 332))
    }

    @Test
    fun parseRelease_invalidJson_returnsNull() {
        assertNull(AppUpdater.parseRelease("not-json", 332))
    }
}
