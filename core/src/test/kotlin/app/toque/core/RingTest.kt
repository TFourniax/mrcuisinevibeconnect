package app.toque.core

import app.toque.core.ui.Led
import app.toque.core.ui.LedWatch
import app.toque.core.ui.Leds
import app.toque.core.ui.RgbLeds
import app.toque.core.ui.RingColors
import app.toque.core.ui.RingLeds
import app.toque.core.ui.RingPolicy
import app.toque.core.ui.RingState
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

    @Test
    fun findsTheRgbRingOfTheMonsieurCuisine() {
        // Ce que montre le SKMC 1200 B2 : trois voyants et le rétroéclairage de l'écran.
        assertEquals(RgbLeds("red", "green", "blue"), RingLeds.detect(listOf("blue", "green", "lcd-backlight", "red")))
        assertEquals(RgbLeds("led_r", "led_g", "led_b"), RingLeds.detect(listOf("led_b", "led_g", "led_r", "vibrator")))
        assertEquals(RgbLeds("mt:red", "mt:green", "mt:blue"), RingLeds.detect(listOf("mt:blue", "mt:green", "mt:red")))
        assertEquals(RgbLeds("red_led", "green_led", "blue_led"), RingLeds.detect(listOf("red_led", "green_led", "blue_led")))
        assertEquals(RgbLeds("r", "g", "b"), RingLeds.detect(listOf("r", "g", "b")))
        // Incomplet, ou seulement l'écran : rien.
        assertNull(RingLeds.detect(listOf("red", "green", "button-backlight")))
        assertNull(RingLeds.detect(listOf("lcd-red", "lcd-green", "lcd-blue")))
        assertNull(RingLeds.detect(emptyList()))
        // Deux familles : celle de l'anneau d'abord, sinon la plus courte.
        assertEquals(RgbLeds("ring:red", "ring:green", "ring:blue"),
            RingLeds.detect(listOf("red", "green", "blue", "ring:red", "ring:green", "ring:blue")))
        assertEquals(RgbLeds("red", "green", "blue"), RingLeds.detect(listOf("red_2", "green_2", "blue_2", "red", "green", "blue")))
    }

    @Test
    fun keepsTheRgbSetBetweenRuns() {
        val rgb = RgbLeds("red", "green", "blue")
        assertEquals(rgb, RgbLeds.decode(rgb.encode()))
        assertNull(RgbLeds.decode("red,green"))
        assertNull(RgbLeds.decode("red,red,blue"))
        assertNull(RgbLeds.decode("red,green,lcd-backlight"))
        assertNull(RgbLeds.decode("red,green,../x"))
        assertNull(RgbLeds.decode(null))
    }

    @Test
    fun scalesColoursToEachChannel() {
        assertEquals(255, RingPolicy.level(255, "high", 255))
        assertEquals(128, RingPolicy.level(255, "medium", 255))
        assertEquals(51, RingPolicy.level(255, "low", 255))
        assertEquals(0, RingPolicy.level(0, "high", 255))
        // Une composante faible reste visible, même à faible force.
        assertEquals(1, RingPolicy.level(1, "low", 255))
        assertEquals(2, RingPolicy.level(10, "low", 255))
        assertEquals(1, RingPolicy.level(255, "high", 1))
        assertEquals(RingColors.WHITE, RingColors.of(null))
        assertEquals(RingColors.WHITE, RingColors.of("fuchsia"))
        assertEquals(RingColors.ORANGE, RingColors.of("orange"))
    }

    @Test
    fun choosesTheColourAndBlinking() {
        val auto = RingPolicy.AUTO
        // Par défaut : la couleur choisie (blanc), quoi que fasse le robot.
        assertEquals(RingColors.WHITE, RingPolicy.look(auto, null, false, RingState.COOKING, false, 0, limit).color)
        assertEquals(RingColors.VIOLET, RingPolicy.look(auto, "violet", false, RingState.ALERT, false, 0, limit).color)
        assertFalse(RingPolicy.look(auto, null, false, RingState.ALERT, false, 0, limit).blink)
        // Selon l'état.
        assertEquals(RingColors.ORANGE, RingPolicy.look(auto, "white", true, RingState.COOKING, false, 0, limit).color)
        assertEquals(RingColors.BLUE, RingPolicy.look(auto, "white", true, RingState.WAITING, false, 0, limit).color)
        assertEquals(RingColors.GREEN, RingPolicy.look(auto, "white", true, RingState.DONE, false, 0, limit).color)
        val alert = RingPolicy.look(auto, "white", true, RingState.ALERT, false, 0, limit)
        assertEquals(RingColors.RED, alert.color)
        assertTrue(alert.blink)
        assertEquals(RingColors.CYAN, RingPolicy.look(auto, "cyan", true, RingState.IDLE, false, 0, limit).color)
        // Allumé : une étape qui attend ou une alerte, même longtemps sans geste ; « prêt » seulement selon l'état.
        assertTrue(RingPolicy.look(auto, null, false, RingState.WAITING, false, 3_600_000, limit).on)
        assertTrue(RingPolicy.look(auto, null, false, RingState.ALERT, true, 3_600_000, limit).on)
        assertFalse(RingPolicy.look(auto, null, false, RingState.DONE, false, 3_600_000, limit).on)
        assertTrue(RingPolicy.look(auto, null, true, RingState.DONE, false, 3_600_000, limit).on)
        // Au repos, derrière l'écran de veille : éteint. « Toujours éteint » l'emporte sur tout.
        assertFalse(RingPolicy.look(auto, null, true, RingState.IDLE, true, 0, limit).on)
        assertFalse(RingPolicy.look(RingPolicy.ALWAYS_OFF, null, true, RingState.ALERT, false, 0, limit).on)
        assertTrue(RingPolicy.look(RingPolicy.ALWAYS_ON, "blue", false, RingState.IDLE, true, 9_999_999, limit).on)
    }

    @Test
    fun remembersAndRestoresTheOriginalState() {
        val leds = listOf(Led("blue", 255, 255, "none"), Led("button-backlight", 0, 255, "timer"), Led("lcd-backlight", 102, 255, ""))
        val text = Leds.encodeOriginal(leds)
        assertEquals("blue=255|none;button-backlight=0|timer", text)
        assertEquals(listOf(Led("blue", 255, 0, "none"), Led("button-backlight", 0, 0, "timer")), Leds.decodeOriginal(text))
        assertTrue(Leds.decodeOriginal("lcd-backlight=0|;../x=1|;bad").isEmpty())
        assertEquals(
            "[ -e /sys/class/leds/button-backlight/brightness ] && { echo none > /sys/class/leds/button-backlight/trigger 2>/dev/null; " +
                "echo 0 > /sys/class/leds/button-backlight/brightness; echo timer > /sys/class/leds/button-backlight/trigger 2>/dev/null; }",
            Leds.restoreCommand("button-backlight", 0, "timer"),
        )
        assertEquals(
            "[ -e /sys/class/leds/red/brightness ] && { echo none > /sys/class/leds/red/trigger 2>/dev/null; echo 255 > /sys/class/leds/red/brightness; }",
            Leds.restoreCommand("red", 255, "none"),
        )
        assertNull(Leds.restoreCommand("lcd-backlight", 0, ""))
        // Un déclencheur douteux n'est jamais écrit.
        assertFalse(Leds.restoreCommand("red", 255, "x; reboot")!!.contains("reboot"))
    }
}
