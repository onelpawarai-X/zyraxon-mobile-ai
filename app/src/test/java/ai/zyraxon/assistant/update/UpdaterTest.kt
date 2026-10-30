package ai.zyraxon.assistant.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The update check is the one place a wrong comparison would leave every user
 * stranded on an old build, so the version rules are pinned here. Robolectric is
 * needed only because the release JSON is read with org.json, which the plain JVM
 * test runtime stubs out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdaterTest {

    @Test
    fun `version compares numerically not as text`() {
        // The bug this guards: "6.10.0" < "6.9.0" as text, so 6.10.0 would never
        // be offered as an update.
        assertTrue(Version.parse("6.10.0")!! > Version.parse("6.9.0")!!)
        assertTrue(Version.parse("v6.5.0")!! > Version.parse("6.4.0")!!)
        assertTrue(Version.parse("7.0.0")!! > Version.parse("6.99.99")!!)
        assertTrue(Version.parse("6.4.1")!! > Version.parse("6.4.0")!!)
        assertFalse(Version.parse("6.4.0")!! > Version.parse("6.4.0")!!)
    }

    @Test
    fun `a two-part version is read as patch zero`() {
        assertEquals(Version(6, 5, 0), Version.parse("v6.5"))
    }

    @Test
    fun `a tag that is not a version is ignored`() {
        assertNull(Version.parse("release-candidate"))
        assertNull(Version.parse(""))
        assertNull(Version.parse(null))
    }

    @Test
    fun `the APK is picked out of a release that also carries notes`() {
        val json = """
            {
              "tag_name": "v6.5.0",
              "body": "ভয়েস ঠিক করা হয়েছে",
              "assets": [
                {"name": "RELEASE_NOTES.md", "browser_download_url": "https://x/notes.md", "size": 10},
                {"name": "ZYRAXON-v6.5.0.apk", "browser_download_url": "https://x/app.apk", "size": 1000},
                {"name": "source.zip", "browser_download_url": "https://x/src.zip", "size": 20}
              ]
            }
        """.trimIndent()

        val release = Updater.parseLatest(json)!!
        assertEquals("v6.5.0", release.tag)
        assertEquals(Version(6, 5, 0), release.version)
        assertEquals("ZYRAXON-v6.5.0.apk", release.apk?.name)
        assertEquals("https://x/app.apk", release.apk?.url)
        assertEquals(1000L, release.apk?.size)
    }

    @Test
    fun `a release with no apk still parses, with a null asset`() {
        val json = """{"tag_name": "v6.5.0", "assets": []}"""
        val release = Updater.parseLatest(json)!!
        assertNull(release.apk)
        assertEquals(Version(6, 5, 0), release.version)
    }

    @Test
    fun `a malformed body is not mistaken for a release`() {
        assertNull(Updater.parseLatest("not json"))
        assertNull(Updater.parseLatest("{}"))
        assertNull(Updater.parseLatest("""{"tag_name": "nightly"}"""))
    }

    @Test
    fun `isNewer only fires on a real step forward`() {
        assertTrue(Updater.isNewer(Version(6, 5, 0), Version(6, 4, 0)))
        assertFalse(Updater.isNewer(Version(6, 4, 0), Version(6, 4, 0)))
        assertFalse(Updater.isNewer(Version(6, 3, 9), Version(6, 4, 0)))
    }

    /**
     * The real GitHub payload for the v6.5.0 release, captured from the live API.
     * A hand-trimmed mock would pass even if GitHub's shape drifted; this cannot.
     */
    @Test
    fun `the real v6_5_0 release payload parses and offers the APK`() {
        val json = javaClass.classLoader!!
            .getResourceAsStream("latest_release_live.json")!!
            .bufferedReader().use { it.readText() }

        val release = Updater.parseLatest(json)!!
        assertEquals("v6.5.0", release.tag)
        assertEquals(Version(6, 5, 0), release.version)
        assertEquals("ZYRAXON-v6.5.0.apk", release.apk?.name)
        assertTrue(release.apk!!.url.endsWith("/download/v6.5.0/ZYRAXON-v6.5.0.apk"))
        assertTrue(release.apk.size > 100_000_000)

        // The build on the phone is 6.5.0, so this release must NOT be offered as
        // an update — the exact "already latest" case the settings pane shows.
        assertFalse(Updater.isNewer(release.version, Version(6, 5, 0)))
        // And 6.4.0 must be offered it.
        assertTrue(Updater.isNewer(release.version, Version(6, 4, 0)))
    }
}
