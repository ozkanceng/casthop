package io.github.ozkanceng.yansit.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateManifestTest {
    @Test
    fun comparesSemVerProperly() {
        val v042 = checkNotNull(SemVer.parse("0.4.2"))
        val v043 = checkNotNull(SemVer.parse("v0.4.3"))
        val v0410 = checkNotNull(SemVer.parse("0.4.10"))
        val v043rc = checkNotNull(SemVer.parse("0.4.3-rc1"))

        assertTrue(v043.isNewerThan(v042))
        assertTrue(v0410.isNewerThan(v043))
        assertFalse(v042.isNewerThan(v042))
        assertFalse(v042.isNewerThan(v043))
        assertFalse(v043rc.isNewerThan(v042))
        assertTrue(v043.isNewerThan(v043rc))
        assertNull(SemVer.parse("invalid"))
        assertNull(SemVer.parse("0.4"))
    }

    @Test
    fun parsesChannelsLanguageAndRejectsInsecureUrls() {
        val json = """
            {
              "schema": 1,
              "latest": "0.5.0",
              "publishedAt": "2026-12-01",
              "releaseUrl": "https://github.com/ozkanceng/casthop/releases/latest",
              "notes": {
                "tr": "Türkçe güncelleme notu",
                "en": "English release note"
              },
              "channels": {
                "androidPlay": {
                  "version": "0.5.0",
                  "url": "https://play.google.com/store/apps/details?id=com.sorumatik.casthop"
                },
                "androidApk": {
                  "version": "0.5.1",
                  "url": "http://insecure.example/casthop.apk"
                }
              }
            }
        """.trimIndent()

        val play = UpdateManifestParser.parse(json, "androidPlay", preferTurkish = true)
        assertNotNull(play)
        assertEquals("0.5.0", play!!.version)
        assertEquals("https://play.google.com/store/apps/details?id=com.sorumatik.casthop", play.url)
        assertEquals("Türkçe güncelleme notu", play.notes)

        val apk = UpdateManifestParser.parse(json, "androidApk", preferTurkish = false)
        assertNotNull(apk)
        assertEquals("0.5.1", apk!!.version)
        assertEquals(UpdateManifestParser.FALLBACK_RELEASE_URL, apk.url)
        assertEquals("English release note", apk.notes)

        assertNull(UpdateManifestParser.parse("{broken", "androidPlay", preferTurkish = true))
    }
}
