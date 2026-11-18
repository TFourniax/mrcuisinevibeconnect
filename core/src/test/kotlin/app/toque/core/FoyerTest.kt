package app.toque.core

import app.toque.core.foyer.FoyerFeed
import app.toque.core.foyer.FoyerPlan
import app.toque.core.foyer.Guided
import app.toque.core.foyer.StatusReport
import app.toque.core.foyer.StatusTracker
import app.toque.core.machine.JobState
import app.toque.core.machine.PauseReason
import app.toque.core.machine.Snapshot
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.StepProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoyerTest {
    private val feed = """
        {"format":"toque-recettes-1","household":"Chez nous","robot":{"name":"Monsieur Cuisine"},
         "recipes":[
          {"id":"foyer-0b6f7c3e-1d2a-4c5b-9e8f-7a6b5c4d3e2f","title":"Risotto","category":"Nos recettes","tags":["Favori"],
           "servings":4,"totalMinutes":30,"source":"foyer","image":"https://example.test/r.jpg",
           "ingredients":[{"group":"","items":[{"qty":"300 g","unit":"","name":"riz arborio","note":""}]}],
           "steps":[{"text":"Faire revenir l'oignon.","machine":{"program":"manuel","seconds":180,"speed":1,"tempC":120,"reverse":false,"accessory":"blade","pulses":0},"needsReview":true},
                    {"text":"Servir."}]},
          {"id":"mc-1234","title":"Recette Lidl déguisée","steps":[{"text":"x"}]},
          {"title":""}
         ],
         "plans":[{"day":"2026-11-20","time":"20:00","title":"Risotto","recipeId":"foyer-0b6f7c3e-1d2a-4c5b-9e8f-7a6b5c4d3e2f"},
                  {"day":"bad","title":"x"}],
         "pendingSteps":2}
    """.trimIndent()

    @Test fun readsTheFeed() {
        val f = FoyerFeed.parse(feed)
        assertEquals("Chez nous", f.household)
        assertEquals("Monsieur Cuisine", f.robotName)
        assertEquals(1, f.recipes.size)
        assertEquals(2, f.skipped)
        val r = f.recipes[0]
        assertEquals(RecipeSource.FOYER, r.source)
        assertEquals("300 g riz arborio", r.allIngredients[0].display)
        assertEquals(StepProgram.MANUAL, r.steps[0].machine?.program)
        assertEquals(120, r.steps[0].machine?.tempC)
        assertTrue(r.steps[0].needsReview)
        assertNull(r.steps[1].machine)
        assertEquals(listOf(FoyerPlan("2026-11-20", "20:00", "Risotto", r.id)), f.plans)
        assertEquals(2, f.pendingSteps)
    }

    @Test fun nextPlan() {
        val plans = listOf(
            FoyerPlan("2026-11-20", "20:00", "Risotto", null),
            FoyerPlan("2026-11-20", "12:30", "Salade", null),
            FoyerPlan("2026-11-21", null, "Soupe", null),
        )
        assertEquals("Salade", FoyerFeed.nextPlan(plans, "2026-11-20", "09:00")?.title)
        assertEquals("Risotto", FoyerFeed.nextPlan(plans, "2026-11-20", "13:00")?.title)
        assertEquals("Soupe", FoyerFeed.nextPlan(plans, "2026-11-20", "21:00")?.title)
        assertNull(FoyerFeed.nextPlan(plans, "2026-11-22", "08:00"))
        assertEquals(plans, FoyerFeed.decodePlans(FoyerFeed.encodePlans(plans)))
    }

    private var now = 1_000_000L
    private val tracker = StatusTracker({ now }, graceMs = 45_000)
    private val running = Snapshot(jobState = JobState.RUNNING.name, jobKind = "MANUAL", jobLabel = "Cuisson manuelle",
        jobSpeed = 1, jobTempLevel = 13, totalMs = 600_000, remainingMs = 300_000)

    @Test fun cookingThenWaitingThenDone() {
        tracker.guided = Guided("foyer-x", "Risotto", 2, 6, "Ajouter le riz.", machineStepsAfter = 2)
        val cooking = tracker.current(running)
        assertEquals("cooking", cooking.state)
        assertEquals(100, cooking.tempC)
        assertEquals(1, cooking.speed)
        assertEquals(now + 300_000, cooking.endsAtMs)

        val waiting = tracker.onJobFinished("Cuisson manuelle")
        assertEquals(StatusReport("waiting", "foyer-x", "Risotto", 3, 6, "Ajouter le riz."), waiting)
        val idle = Snapshot(jobState = JobState.FINISHED.name)
        assertFalse(tracker.current(idle).notify)
        now += 46_000
        assertTrue(tracker.current(idle).notify)
        // Once only.
        assertFalse(tracker.current(idle).notify)

        tracker.guided = Guided("foyer-x", "Risotto", 5, 6, "", machineStepsAfter = 0)
        assertEquals("cooking", tracker.current(running).state)
        assertEquals("done", tracker.onJobFinished("").state)
        now += 46_000
        val done = tracker.current(idle)
        assertEquals("done", done.state)
        assertTrue(done.notify)
    }

    @Test fun nobodyIsNotifiedWhenSomeoneIsThere() {
        tracker.guided = Guided("foyer-x", "Risotto", 5, 6, "", machineStepsAfter = 0)
        tracker.onJobFinished("")
        now += 10_000
        tracker.interaction()
        now += 60_000
        val r = tracker.current(Snapshot())
        assertEquals("done", r.state)
        assertFalse(r.notify)
        // Out of the recipe and back at the robot: idle again.
        tracker.guided = null
        tracker.interaction()
        assertEquals("idle", tracker.current(Snapshot()).state)
    }

    @Test fun safetyStopsAreAnnouncedAtOnce() {
        tracker.current(running)
        val paused = tracker.current(running.copy(jobState = JobState.PAUSED.name, pauseReason = PauseReason.SAFETY.name))
        assertEquals("alert", paused.state)
        assertTrue(paused.notify)
        assertFalse(tracker.current(running.copy(jobState = JobState.PAUSED.name, pauseReason = PauseReason.SAFETY.name)).notify)
        // The lid opened by someone: a pause, no alert.
        assertEquals("paused", tracker.current(running.copy(jobState = JobState.PAUSED.name, pauseReason = PauseReason.LID.name)).state)
        val fault = tracker.current(running.copy(faultCode = "SILENT", faultMessage = "La base ne répond plus"))
        assertEquals("alert", fault.state)
        assertEquals("La base ne répond plus", fault.alert)
    }

    @Test fun aSafetyStopStaysUntilSomeoneComes() {
        tracker.current(running)
        val alert = tracker.current(running.copy(faultCode = "SILENT", faultMessage = "La base ne répond plus"))
        assertTrue(alert.notify)
        // The job is aborted by the stop and the next samples show no job: still an alert, no new push.
        tracker.onJobAborted()
        val after = tracker.current(Snapshot(faultCode = "SILENT", faultMessage = "La base ne répond plus"))
        assertEquals("alert", after.state)
        assertFalse(after.notify)
        assertEquals("alert", tracker.current(Snapshot()).state)
        tracker.interaction()
        assertEquals("idle", tracker.current(Snapshot()).state)
    }

    @Test fun aStoppedJobSaysNothing() {
        tracker.current(running)
        tracker.onJobFinished("x")
        tracker.onJobAborted()
        assertEquals("idle", tracker.current(Snapshot()).state)
    }

    @Test fun reportsAreSentOnlyWhenSomethingChanges() {
        val a = StatusReport("cooking", endsAtMs = 100_000)
        assertFalse(a.copy(endsAtMs = 110_000).worthSending(a))
        assertTrue(a.copy(endsAtMs = 200_000).worthSending(a))
        assertTrue(a.copy(state = "paused").worthSending(a))
        assertTrue(a.copy(notify = true).worthSending(a))
        assertTrue(a.worthSending(null))
    }
}
