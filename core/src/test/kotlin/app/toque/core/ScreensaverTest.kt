package app.toque.core

import app.toque.core.foyer.FoyerFeed
import app.toque.core.foyer.MediaSniff
import app.toque.core.foyer.ScreensaverConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensaverTest {
    private val id = "0b9c6a4e-3f0e-4c1b-9d55-6a7f1e2d3c4b"

    @Test
    fun readsTheFeedBlock() {
        val feed = FoyerFeed.parse(
            """{"household":"Maison","robot":{"name":"MC"},"recipes":[],"plans":[],"pendingSteps":0,
              "screensaver":{"mode":"media","delayMinutes":3,"itemSeconds":30,"items":[
                {"id":"$id","kind":"gif","url":"https://foyer-life.netlify.app/api/robot/screensaver/$id?v=1a2b3c4d"},
                {"id":"not-an-id","kind":"gif","url":"https://x/y"},
                {"id":"$id","kind":"gif","url":"https://foyer-life.netlify.app/dup"},
                {"id":"1b9c6a4e-3f0e-4c1b-9d55-6a7f1e2d3c4b","kind":"exe","url":"https://x/y"},
                {"id":"2b9c6a4e-3f0e-4c1b-9d55-6a7f1e2d3c4b","kind":"video","url":"http://insecure/clip.mp4"}
              ]}}""",
        )
        val s = feed.screensaver!!
        assertEquals(ScreensaverConfig.MEDIA, s.mode)
        assertEquals(3, s.delayMinutes)
        assertEquals(30, s.itemSeconds)
        assertEquals(1, s.items.size)
        assertEquals("1a2b3c4d", s.items[0].version)
        assertEquals("$id-1a2b3c4d", s.items[0].stem)
    }

    @Test
    fun clampsAndFallsBackToTheClock() {
        val c = ScreensaverConfig.fromMap(mapOf("mode" to "disco", "delayMinutes" to 0, "itemSeconds" to 999))
        assertEquals(ScreensaverConfig.CLOCK, c.mode)
        assertEquals(1, c.delayMinutes)
        assertEquals(300, c.itemSeconds)
        assertNull(FoyerFeed.parse("""{"recipes":[],"plans":[]}""").screensaver)
        assertEquals(ScreensaverConfig.DEFAULT, ScreensaverConfig.decode("not json"))
        assertEquals(ScreensaverConfig.DEFAULT, ScreensaverConfig.decode(""))
    }

    @Test
    fun survivesARestart() {
        val c = ScreensaverConfig.fromMap(
            mapOf("mode" to "media", "delayMinutes" to 10, "itemSeconds" to 60,
                "items" to listOf(mapOf("id" to id, "kind" to "video", "url" to "https://foyer-life.netlify.app/api/robot/screensaver/$id?v=ff00"))),
        )
        assertEquals(c, ScreensaverConfig.decode(ScreensaverConfig.encode(c)))
    }

    @Test
    fun recognisesFilesByTheirFirstBytes() {
        fun bytes(vararg v: Int) = ByteArray(16).also { b -> v.forEachIndexed { i, x -> b[i] = x.toByte() } }
        assertEquals("gif", MediaSniff.ext("GIF89a..........".toByteArray()))
        assertEquals("gif", MediaSniff.ext("GIF87a..........".toByteArray()))
        assertEquals("mp4", MediaSniff.ext(bytes(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D)))
        assertEquals("jpg", MediaSniff.ext(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("png", MediaSniff.ext(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals("webp", MediaSniff.ext("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(MediaSniff.ext("<html><body>....".toByteArray()))
        assertNull(MediaSniff.ext(ByteArray(3)))
        assertTrue(MediaSniff.fits("video", "mp4"))
        assertFalse(MediaSniff.fits("image", "mp4"))
        assertFalse(MediaSniff.fits("gif", "jpg"))
    }
}
