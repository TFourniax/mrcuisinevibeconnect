package app.toque.core

import app.toque.core.foyer.AgentRun
import app.toque.core.foyer.Agents
import app.toque.core.foyer.Agents.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentsTest {
    private val min = 60_000L
    private val hour = 60 * min
    // 25/11/2026 18:00 UTC.
    private val now = Agents.parseIso("2026-11-25T18:00:00Z")!!

    private fun run(state: String, sinceAgo: Long, updatedAgo: Long = sinceAgo, agent: String = "claude", session: String = state) =
        AgentRun(agent, session, "cloud", state, "Titre", "projet", "Thomas", now - sinceAgo, now - updatedAgo)

    @Test fun readsIsoTimes() {
        assertEquals(1795629600000L, now)
        assertEquals(now + 123, Agents.parseIso("2026-11-25T18:00:00.123456+00:00"))
        assertEquals(now, Agents.parseIso("2026-11-25T19:00:00+01:00"))
        assertEquals(now, Agents.parseIso("2026-11-25T13:30:00-04:30"))
        assertEquals(now, Agents.parseIso("2026-11-25 18:00:00+00"))
        assertEquals(0L, Agents.parseIso("1970-01-01T00:00:00Z"))
        assertEquals(951782400000L, Agents.parseIso("2000-02-29T00:00:00Z"))
        assertNull(Agents.parseIso("hier"))
        assertNull(Agents.parseIso("2026-13-01T00:00:00Z"))
        assertNull(Agents.parseIso(null))
    }

    @Test fun readsFoyersAnswer() {
        val body = """[
          {"agent":"claude","session_key":"cse_1","location":"cloud","state":"waiting","title":"Refonte\nde Toque",
           "project":"mrcuisinevibeconnect","link":"https://claude.ai/code/session_1","who":"Thomas",
           "state_since":"2026-11-25T17:52:00.5+00:00","updated_at":"2026-11-25T17:58:00+00:00"},
          {"agent":"hermes","session_key":"telegram","location":null,"state":"done","title":null,"project":null,"who":null,
           "state_since":"2026-11-25T16:00:00+00:00","updated_at":"2026-11-25T16:00:00+00:00"},
          {"agent":"codex","state":"sleeping","updated_at":"2026-11-25T17:00:00+00:00"},
          {"agent":"codex","state":"working"},
          {"state":"working","updated_at":"2026-11-25T17:00:00+00:00"}
        ]"""
        val runs = Agents.parse(body)
        assertEquals(2, runs.size)
        val c = runs[0]
        assertEquals("claude", c.agent)
        assertEquals("cse_1", c.session)
        assertEquals("cloud", c.location)
        assertEquals("Refonte de Toque", c.title)
        assertEquals("Thomas", c.who)
        assertEquals(now - 8 * min + 500, c.since)
        assertEquals(now - 2 * min, c.updated)
        assertEquals("", runs[1].location)
        assertEquals("", runs[1].title)
        assertEquals(emptyList<AgentRun>(), Agents.parse("pas du json"))
        assertEquals(emptyList<AgentRun>(), Agents.parse("{}"))
        // La copie gardée sur le robot redonne la même chose.
        assertEquals(runs, Agents.decode(Agents.encode(runs)))
        assertEquals(emptyList<AgentRun>(), Agents.decode(""))
    }

    @Test fun showsTheUrgentFirstAndForgetsOldEnds() {
        val runs = listOf(
            run(Agents.DONE, 1 * hour, session = "fini"),
            run(Agents.WORKING, 20 * min, 1 * min, session = "tourne"),
            run(Agents.WAITING, 5 * min, session = "attend"),
            run(Agents.IDLE, 1 * min, session = "arrete"),
            run(Agents.DONE, 13 * hour, session = "vieux"),
            run(Agents.ERROR, 2 * hour, session = "erreur"),
        )
        assertEquals(listOf("attend", "erreur", "tourne", "fini"), Agents.shown(runs, now).map { it.session })
        val many = (1..12).map { run(Agents.DONE, it * min, session = "s$it") }
        assertEquals(Agents.MAX_LINES, Agents.shown(many, now).size)
        assertEquals("s1", Agents.shown(many, now).first().session)
    }

    @Test fun theIconSaysWhatMattersMost() {
        assertEquals(Signal.NONE, Agents.signal(emptyList(), now, 0))
        assertEquals(Signal.NONE, Agents.signal(listOf(run(Agents.IDLE, min), run(Agents.DONE, 13 * hour)), now, 0))
        val working = run(Agents.WORKING, 10 * min, 1 * min)
        assertEquals(Signal.WORKING, Agents.signal(listOf(working), now, 0))
        val done = run(Agents.DONE, 5 * min)
        assertEquals(Signal.DONE, Agents.signal(listOf(working, done), now, 0))
        // Carte ouverte depuis : le « terminé » est vu.
        assertEquals(Signal.WORKING, Agents.signal(listOf(working, done), now, now - min))
        assertEquals(Signal.QUIET, Agents.signal(listOf(done), now, now - min))
        val waiting = run(Agents.WAITING, 30 * min)
        // Un agent qui attend reste signalé tant qu'il attend, carte vue ou non.
        assertEquals(Signal.NEEDS_YOU, Agents.signal(listOf(working, done, waiting), now, now))
        // Une attente oubliée depuis 13 h reste dans la carte, sans colorer l'icône.
        assertEquals(Signal.QUIET, Agents.signal(listOf(run(Agents.WAITING, 13 * hour, 13 * hour)), now, 0))
        assertEquals(1, Agents.shown(listOf(run(Agents.WAITING, 13 * hour, 13 * hour)), now).size)
        val error = run(Agents.ERROR, 3 * min)
        assertEquals(Signal.NEEDS_YOU, Agents.signal(listOf(error), now, 0))
        assertEquals(Signal.QUIET, Agents.signal(listOf(error), now, now))
        // En cours mais sans nouvelle depuis 40 min : pas de pastille « ça tourne ».
        assertEquals(Signal.QUIET, Agents.signal(listOf(run(Agents.WORKING, 2 * hour, 40 * min)), now, 0))
    }

    @Test fun asksFoyerOftenOnlyWhenSomethingRuns() {
        val working = run(Agents.WORKING, 10 * min, 1 * min)
        assertEquals(10_000L, Agents.pollDelay(emptyList(), now, open = true, night = false))
        assertEquals(20_000L, Agents.pollDelay(listOf(working), now, open = false, night = true))
        assertEquals(20_000L, Agents.pollDelay(listOf(run(Agents.WAITING, hour)), now, open = false, night = false))
        assertEquals(60_000L, Agents.pollDelay(listOf(run(Agents.DONE, min)), now, open = false, night = false))
        assertEquals(60_000L, Agents.pollDelay(listOf(run(Agents.WORKING, 5 * hour, 3 * hour)), now, open = false, night = false))
        assertEquals(300_000L, Agents.pollDelay(emptyList(), now, open = false, night = true))
    }

    @Test fun wordsForTheCard() {
        assertEquals("Claude Code", Agents.name("claude"))
        assertEquals("Hermès", Agents.name("hermes"))
        assertEquals("Codex", Agents.name("codex"))
        assertEquals("Mon harness", Agents.name("mon-harness"))
        assertEquals("En cours", Agents.stateLabel(run(Agents.WORKING, min), now))
        assertEquals("T'attend", Agents.stateLabel(run(Agents.WAITING, min), now))
        assertEquals("Silencieux", Agents.stateLabel(run(Agents.WORKING, hour, 45 * min), now))
        assertEquals("depuis 12 min", Agents.timeLabel(run(Agents.WORKING, 12 * min, 0), now))
        assertEquals("il y a 3 h", Agents.timeLabel(run(Agents.DONE, 3 * hour + 20 * min), now))
        assertEquals("à l'instant", Agents.timeLabel(run(Agents.DONE, 20_000L), now))
        assertEquals("aucune nouvelle depuis 45 min", Agents.timeLabel(run(Agents.WORKING, hour, 45 * min), now))
        assertEquals("2 j", Agents.duration(50 * hour))
        val lines = listOf(run(Agents.WAITING, min), run(Agents.WORKING, min), run(Agents.WORKING, min), run(Agents.DONE, min))
        assertEquals("1 t'attend · 2 en cours · 1 terminé", Agents.summary(lines, now))
        assertEquals("2 t'attendent · 2 terminés", Agents.summary(listOf(run(Agents.WAITING, min), run(Agents.WAITING, min), run(Agents.DONE, min), run(Agents.DONE, min)), now))
        assertEquals("Rien en cours", Agents.summary(emptyList(), now))
        assertTrue(Agents.iconDescription(Signal.NEEDS_YOU, lines, now).startsWith("Agents : "))
        assertFalse(Agents.stale(run(Agents.DONE, 5 * hour), now))
    }
}
