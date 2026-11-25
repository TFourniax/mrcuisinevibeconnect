package app.toque.core

import app.toque.core.machine.EventType
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobState
import app.toque.core.machine.Limits
import app.toque.core.machine.ProgramCatalog
import app.toque.core.machine.Programs
import app.toque.core.machine.SafetyPolicy
import app.toque.core.machine.Setting
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.Operation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les dix programmes (1.0.12) : réglages validés, règles de sécurité, déroulement sur la base simulée. */
class ProgramsTest {
    @Test fun catalogHasTheTenProgramsInScreenOrder() {
        assertEquals(
            listOf(JobKind.KNEAD, JobKind.STEAM, JobKind.TOAST, JobKind.SIMMER, JobKind.RICE, JobKind.EGGS,
                JobKind.WATER, JobKind.BLEND, JobKind.SMOOTHIE, JobKind.PREWASH),
            ProgramCatalog.all.map { it.kind },
        )
        for (p in ProgramCatalog.all) {
            assertTrue(p.label.isNotBlank() && p.note.isNotBlank() && p.prep.isNotBlank())
            assertTrue("${p.kind} : choix par défaut", p.choices.isEmpty() || p.defaultChoice in p.choices.indices)
        }
    }

    @Test fun everySettingOfEveryProgramPassesTheSafetyRulesCold() {
        for (p in ProgramCatalog.all) {
            val choices = if (p.choices.isEmpty()) listOf(0) else p.choices.indices.toList()
            for (c in choices) {
                val durations = if (p.adjustable) listOf(p.minSeconds, p.startSeconds(c), p.maxSeconds) else listOf(p.startSeconds(c))
                for (s in durations) {
                    val spec = p.job(s, c)
                    assertEquals("${p.kind}", p.kind, spec.kind)
                    assertEquals("${p.kind} $s s : ${SafetyPolicy.checkJob(spec, 20)}", emptyList<Any>(), SafetyPolicy.checkJob(spec, 20))
                    if (!spec.cyclic) assertEquals("${p.kind} : durées des phases", spec.totalMs, spec.phases.sumOf { it.durationMs })
                }
            }
        }
    }

    @Test fun validatedTimings() {
        val eggs = ProgramCatalog.of(JobKind.EGGS)!!
        assertEquals(listOf(7 * 60, 9 * 60, 12 * 60), eggs.choices.indices.map { eggs.startSeconds(it) })
        assertEquals("Mollet", eggs.choices[eggs.defaultChoice].label)
        val rice = ProgramCatalog.of(JobKind.RICE)!!
        assertEquals(listOf(30 * 60, 25 * 60, 45 * 60), rice.choices.indices.map { rice.startSeconds(it) })
        assertEquals(90, ProgramCatalog.of(JobKind.KNEAD)!!.seconds)
        assertEquals(60, ProgramCatalog.of(JobKind.SMOOTHIE)!!.seconds)
        assertEquals(160_000L, Programs.prewash().totalMs)
        // Le pas reste dans les bornes.
        assertEquals(20 * 60, eggs.step(20 * 60, 1))
        assertEquals(5 * 60, eggs.step(5 * 60, -1))
        assertEquals(8 * 60, eggs.step(9 * 60, -1))
    }

    @Test fun settingsAsChips() {
        assertEquals(listOf("95 °C", "Vitesse 1", "Inverse"), ProgramCatalog.settings(Programs.simmer()))
        assertEquals(listOf("Vapeur 120 °C", "Lame immobile"), ProgramCatalog.settings(Programs.eggs()))
        assertEquals(listOf("Vitesse 5 → 10"), ProgramCatalog.settings(Programs.blend()))
        assertEquals(listOf("Vitesse 4", "Sens alterné"), ProgramCatalog.settings(Programs.knead()))
        assertEquals(listOf("130 °C", "Vitesse 1", "Inverse"), ProgramCatalog.settings(Programs.toast()))
        assertEquals(listOf("80 °C", "Vitesse 1"), ProgramCatalog.settings(Programs.water(80)))
    }

    @Test fun newProgramsFollowTheManualRules() {
        // Pas de chauffe en mixant, vitesse 3 au plus en chauffe, vapeur sans lame.
        val hotBlend = Setting(Operation.STANDARD, 5, 9, Direction.CLOCKWISE)
        assertTrue(SafetyPolicy.checkSetting(hotBlend, JobKind.SMOOTHIE, app.toque.core.machine.Accessory.BLADE, 20, false).any { it.code == "blend_heat" })
        val fastHot = Setting(Operation.STANDARD, 5, 12, Direction.COUNTER_CLOCKWISE)
        assertTrue(SafetyPolicy.checkSetting(fastHot, JobKind.SIMMER, app.toque.core.machine.Accessory.BLADE, 20, false).any { it.code == "heated_speed" })
        val spinningSteam = Setting(Operation.STEAM, 3, 17, Direction.CLOCKWISE)
        assertTrue(SafetyPolicy.checkSetting(spinningSteam, JobKind.EGGS, app.toque.core.machine.Accessory.BLADE, 20, false).any { it.code == "steam_speed" })
        val wrongOp = Setting(Operation.STEAM, 0, 13, Direction.CLOCKWISE)
        assertTrue(SafetyPolicy.checkSetting(wrongOp, JobKind.RICE, app.toque.core.machine.Accessory.BLADE, 20, false).any { it.code == "operation" })
        // Mixer chaud : confirmation demandée, comme ailleurs.
        assertTrue(SafetyPolicy.checkJob(Programs.blend(), 80).any { it.code == SafetyPolicy.HOT_BLEND })
        assertTrue(SafetyPolicy.checkJob(Programs.blend().copy(hotBlendConfirmed = true), 80).isEmpty())
        // Chauffer l'eau : jamais plus de 15 min.
        assertTrue(SafetyPolicy.checkJob(Programs.water(80).copy(totalMs = Limits.MAX_MS_WATER + 1_000, phases = listOf(
            Programs.water(80).phases[0].copy(durationMs = Limits.MAX_MS_WATER + 1_000))), 20).any { it.code == "too_long" })
    }

    @Test fun heatingWaterStopsAtTheTemperature() {
        val b = Bench().ready()
        assertTrue(b.c.startJob(Programs.water(80)).ok)
        var seconds = 0
        while (b.snap.state != JobState.FINISHED && seconds < 600) {
            b.step(1_000)
            seconds++
        }
        assertEquals(JobState.FINISHED, b.snap.state)
        assertTrue("fini à la température, bien avant 15 min ($seconds s)", seconds < 200)
        assertTrue(b.events.any { it.type == EventType.JOB_FINISHED })
        // Arrêt à 2 °C de la consigne : une sonde réelle se stabilise souvent un degré en dessous.
        assertEquals(78, Programs.water(80).untilTempC)
        assertEquals(68, Programs.water(70).untilTempC)
        assertTrue(b.snap.actualTempC >= 78)
        // 100 °C : arrêt à 97 °C, que la sonde atteint même si elle n'affiche pas 100.
        assertEquals(97, Programs.water(100).untilTempC)
        assertEquals(Mcc.levelForCelsius(100), Programs.water(100).phases[0].setting.temperatureLevel)
    }

    @Test fun heatingWaterThatNeverGetsThereStopsAtFifteenMinutes() {
        // La sonde plafonne sous la température d'arrêt (base simulée : elle s'arrête à la consigne).
        val b = Bench().ready()
        assertTrue(b.c.startJob(Programs.water(80).copy(untilTempC = 85)).ok)
        var seconds = 0
        while (b.snap.state != JobState.FINISHED && seconds < 20 * 60) {
            b.step(1_000)
            seconds++
        }
        assertEquals(JobState.FINISHED, b.snap.state)
        assertTrue("fin au plafond de 15 min ($seconds s)", seconds in 15 * 60 - 2..15 * 60 + 5)
    }

    @Test fun heatingWaterStopTemperatureMustBeInTheTable() {
        for (bad in listOf(-1, 131)) {
            assertTrue(SafetyPolicy.checkJob(Programs.water(80).copy(untilTempC = bad), 20).any { it.code == "until_temp" })
        }
        assertTrue(SafetyPolicy.checkJob(Programs.water(80), 20).none { it.code == "until_temp" })
    }

    @Test fun blendRampsUpWithoutStopping() {
        val b = Bench().ready()
        assertTrue(b.c.startJob(Programs.blend(20_000)).ok)
        b.step(25_000)
        val speeds = b.tx.filter { it[1] == Operation.STANDARD.wire }.map { it[2] }.distinct()
        assertTrue("vitesses vues : $speeds", speeds.containsAll(listOf(5, 7, 10)))
        assertEquals(JobState.FINISHED, b.snap.state)
        assertFalse(b.snap.hasFault)
    }

    @Test fun prewashHeatsThenRinsesCold() {
        val b = Bench().ready()
        assertTrue(b.c.startJob(Programs.prewash()).ok)
        b.step(170_000)
        val standard = b.tx.filter { it[1] == Operation.STANDARD.wire }
        assertTrue(standard.any { it[2] == 2 && it[3] == Mcc.levelForCelsius(50) })
        assertTrue(standard.any { it[2] == 5 && it[3] == 0 })
        assertEquals(JobState.FINISHED, b.snap.state)
    }

    @Test fun simmerTurnsTheOtherWayAt95() {
        val b = Bench().ready()
        assertTrue(b.c.startJob(Programs.simmer(60_000)).ok)
        b.step(5_000)
        val frames = b.tx.filter { it[1] == Operation.STANDARD.wire && it[2] > 0 }
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { it[2] == 1 && it[3] == Mcc.levelForCelsius(95) && it[4] == Direction.COUNTER_CLOCKWISE.wire })
    }
}
