package app.toque.core

import app.toque.core.ui.Led
import app.toque.core.ui.LedWatch
import app.toque.core.ui.Leds
import app.toque.core.ui.RingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingTest {
    private val limit = RingPolicy.idleLimitMs(screensaverOff = false, screensaverDelayMinutes = 5)

    @Test
    fun autoIsLitWhileSomeoneUsesTheRobot() {
        assertTrue(RingPolicy.lit(RingPolicy.AUTO, busy = false, screensaverShown = false, idleMs = 10_000, idleLimitMs = limit))
        assertFalse(RingPolicy.lit(RingPolicy.AUTO, busy = false, screensaverShown = false, idleMs = limit, idleLimitMs = limit))
        // Derrière l'écran de veille : éteint, même juste après un geste.
        assertFalse(RingPolicy.lit(RingPolicy.AUTO, busy = false, screensaverShown = true, idleMs = 0, idleLimitMs = limit))
        // Pendant une cuisson : toujours allumé, même sans geste depuis longtemps.
        assertTrue(RingPolicy.lit(RingPolicy.AUTO, busy = true, screensaverShown = false, idleMs = 3_600_000, idleLimitMs = limit))
    }

    @Test
    fun fixedModesIgnoreEverythingElse() {
        assertTrue(RingPolicy.lit(RingPolicy.ALWAYS_ON, busy = false, screensaverShown = true, idleMs = 9_999_999, idleLimitMs = limit))
        assertFalse(RingPolicy.lit(RingPolicy.ALWAYS_OFF, busy = true, screensaverShown = false, idleMs = 0, idleLimitMs = limit))
        assertEquals(RingPolicy.AUTO, RingPolicy.mode("bizarre"))
        assertEquals(RingPolicy.AUTO, RingPolicy.mode(null))
    }

    @Test
    fun idleLimitFollowsTheScreensaver() {
        assertEquals(5 * 60_000L, limit)
        assertEquals(2 * 60_000L, RingPolicy.idleLimitMs(screensaverOff = true, screensaverDelayMinutes = 15))
        assertEquals(60 * 60_000L, RingPolicy.idleLimitMs(screensaverOff = false, screensaverDelayMinutes = 999))
    }

    @Test
    fun readsTheLedListing() {
        val text = """
            button-backlight|255|255|[none] timer heartbeat
            red|0|255|none [timer]
            lcd-backlight|102|255|
            bad name|1|1|
            ../etc|1|1|
            vibrator|0||
        """.trimIndent()
        assertEquals(
            listOf(
                Led("button-backlight", 255, 255, "none"),
                Led("lcd-backlight", 102, 255, ""),
                Led("red", 0, 255, "timer"),
                Led("vibrator", 0, 255, ""),
            ),
            Leds.parseList(text),
        )
        assertTrue(Leds.parseList("").isEmpty())
    }

    @Test
    fun writesOnlyChecked() {
        assertEquals(
            "[ -e /sys/class/leds/button-backlight/brightness ] && { echo none > /sys/class/leds/button-backlight/trigger 2>/dev/null; echo 0 > /sys/class/leds/button-backlight/brightness; }",
            Leds.setCommand("button-backlight", 0),
        )
        assertNull(Leds.setCommand("x; reboot", 0))
        assertNull(Leds.setCommand("../../power", 0))
        assertNull(Leds.setCommand("red", -1))
        assertNull(Leds.setCommand("red", 1_000_000))
        assertFalse(Leds.validName(".."))
        assertTrue(Leds.isScreen("lcd-backlight"))
        assertFalse(Leds.isScreen("button-backlight"))
    }

    @Test
    fun watchSeesWhatMoves() {
        val w = LedWatch()
        assertNull(w.sample("button-backlight", 255, 0))
        assertNull(w.sample("red", 0, 0))
        assertNull(w.sample("button-backlight", 255, 400))
        assertEquals(LedWatch.Change("button-backlight", 800, 255, 0), w.sample("button-backlight", 0, 800))
        w.sample("button-backlight", 255, 1200)
        w.sample("red", 255, 1600)
        assertEquals(listOf("button-backlight" to 2, "red" to 1), w.moved())
        assertEquals("button-backlight" to 255, Leds.parseSample("button-backlight=255"))
        assertNull(Leds.parseSample("--"))
        assertNull(Leds.parseSample("x y=3"))
        assertTrue(Leds.watchCommand(450).contains("-lt 450"))
    }
}
