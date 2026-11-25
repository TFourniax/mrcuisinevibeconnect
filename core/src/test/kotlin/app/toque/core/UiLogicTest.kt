package app.toque.core

import app.toque.core.foyer.FeedChanges
import app.toque.core.foyer.FoyerPlan
import app.toque.core.foyer.NoticeEvent
import app.toque.core.foyer.NoticeLog
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeFilter
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import app.toque.core.ui.SvgPath
import app.toque.core.ui.ThemeClock
import app.toque.core.ui.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logique de l'interface 1.0.12 : thème jour/soir, icônes, « À savoir ». */
class UiLogicTest {
    // ------------------------------------------------------------------ thème

    @Test fun autoThemeFollowsTheClock() {
        assertTrue(ThemeClock.autoDark(6 * 60 + 59))
        assertFalse(ThemeClock.autoDark(7 * 60))
        assertFalse(ThemeClock.autoDark(19 * 60 + 59))
        assertTrue(ThemeClock.autoDark(20 * 60))
        assertTrue(ThemeClock.autoDark(0))
        assertEquals(60, ThemeClock.minutesToNextSwitch(6 * 60))
        assertEquals(1, ThemeClock.minutesToNextSwitch(19 * 60 + 59))
        assertEquals(11 * 60, ThemeClock.minutesToNextSwitch(20 * 60))
        assertEquals(2 * 60, ThemeClock.minutesToNextSwitch(18 * 60))
    }

    @Test fun moonButtonHoldsUntilTheNextSwitch() {
        val now = 1_000_000_000L
        val afternoon = 15 * 60
        // Automatique, l'après-midi : clair ; la lune passe au sombre jusqu'à 20 h.
        assertFalse(ThemeClock.dark(ThemeMode.AUTO, afternoon, now, null, 0))
        val t = ThemeClock.toggle(ThemeMode.AUTO, currentlyDark = false, minuteOfDay = afternoon, nowMs = now)
        assertEquals(ThemeMode.AUTO, t.mode)
        assertEquals(true, t.overrideDark)
        assertEquals(now + 5 * 3600_000L, t.overrideUntilMs)
        assertTrue(ThemeClock.dark(ThemeMode.AUTO, afternoon, now + 60_000, t.overrideDark, t.overrideUntilMs))
        // À 20 h, l'automatique reprend (sombre de toute façon), le lendemain matin clair.
        assertFalse(ThemeClock.dark(ThemeMode.AUTO, 8 * 60, now + 17 * 3600_000L, t.overrideDark, t.overrideUntilMs))
        // Le soleil, appuyé ensuite, revient à l'heure : plus de choix gardé.
        val back = ThemeClock.toggle(ThemeMode.AUTO, currentlyDark = true, minuteOfDay = afternoon, nowMs = now + 60_000)
        assertNull(back.overrideDark)
        // Thème fixé : la lune change simplement le réglage.
        assertEquals(ThemeMode.DARK, ThemeClock.toggle(ThemeMode.LIGHT, false, afternoon, now).mode)
        assertEquals(ThemeMode.LIGHT, ThemeClock.toggle(ThemeMode.DARK, true, afternoon, now).mode)
        assertTrue(ThemeClock.dark(ThemeMode.DARK, afternoon, now, false, Long.MAX_VALUE))
        assertEquals(ThemeMode.AUTO, ThemeMode.of("n'importe quoi"))
    }

    // ------------------------------------------------------------------ icônes

    @Test fun svgPathBasics() {
        val ops = SvgPath.parse("M10 10h5v5H10z")
        assertEquals(
            listOf(SvgPath.Op.Move(10f, 10f), SvgPath.Op.Line(15f, 10f), SvgPath.Op.Line(15f, 15f), SvgPath.Op.Line(10f, 15f), SvgPath.Op.Close),
            ops,
        )
        // Relatif, séparateurs compacts, paires implicites après M.
        val rel = SvgPath.parse("m1 1 2-1.5.5.5")
        assertEquals(SvgPath.Op.Line(3f, -0.5f), rel[1])
        assertEquals(SvgPath.Op.Line(3.5f, 0f), rel[2])
    }

    @Test fun svgArcsEndWhereTheySay() {
        // Quart de cercle de rayon 4 : de (4,0) à (0,4) autour de (0,0).
        val ops = SvgPath.parse("M4 0A4 4 0 0 1 0 4")
        val last = ops.last() as SvgPath.Op.Cubic
        assertEquals(0f, last.x, 1e-4f)
        assertEquals(4f, last.y, 1e-4f)
        // Le milieu de la courbe reste sur le cercle (à 1 % près).
        val mx = 0.125f * 4 + 0.375f * last.x1 + 0.375f * last.x2 + 0.125f * last.x
        val my = 0.125f * 0 + 0.375f * last.y1 + 0.375f * last.y2 + 0.125f * last.y
        assertEquals(4.0, Math.hypot(mx.toDouble(), my.toDouble()), 0.04)
        // Drapeaux collés et cercle complet en deux arcs.
        val circle = SvgPath.parse(SvgPath.circle(12f, 12f, 3f))
        assertTrue(circle.count { it is SvgPath.Op.Cubic } >= 4)
        val compact = SvgPath.parse("M2 2a2 2 0 012 2")
        assertTrue(compact.last() is SvgPath.Op.Cubic)
    }

    @Test fun lucideIconsParse() {
        val samples = listOf(
            "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9",
            "M10.3 21a1.94 1.94 0 0 0 3.4 0",
            "M14 4v10.54a4 4 0 1 1-4 0V4a2 2 0 0 1 4 0Z",
            "M12 22c6.23-.05 7.87-5.57 7.5-10-.36-4.34-3.95-9.96-7.5-10-3.55.04-7.14 5.66-7.5 10-.37 4.43 1.27 9.95 7.5 10z",
            "M15.4 15.63a7.875 6 135 1 1 6.23-6.23 4.3 3.43 135 0 0-6.23 6.23",
            "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z",
            SvgPath.rect(3f, 3f, 7f, 7f, 1.5f),
        )
        for (s in samples) assertTrue(s, SvgPath.parse(s).isNotEmpty())
    }

    // ------------------------------------------------------------------ « À savoir »

    @Test fun noticeLogKeepsThreeDaysAndTracksWhatWasRead() {
        val day = 24 * 3600_000L
        var log = emptyList<NoticeEvent>()
        log = NoticeLog.add(log, NoticeEvent("recipes:a", "recipes", listOf("Dahl"), 0), 0)
        log = NoticeLog.add(log, NoticeEvent("menu:b", "menu", listOf("Jeudi"), day), day)
        assertEquals(2, NoticeLog.unread(log))
        assertEquals("menu:b", log.first().key)
        log = NoticeLog.markRead(log, "recipes:a")
        assertEquals(1, NoticeLog.unread(log))
        // Même clé : remplacé et de nouveau non lu, en tête.
        log = NoticeLog.add(log, NoticeEvent("recipes:a", "recipes", listOf("Dahl", "Tarte"), day + 1), day + 1)
        assertEquals(2, log.size)
        assertEquals(listOf("Dahl", "Tarte"), log.first().items)
        // Aller-retour JSON.
        assertEquals(log, NoticeLog.decode(NoticeLog.encode(log)))
        assertEquals(emptyList<NoticeEvent>(), NoticeLog.decode("pas du json"))
        // Après trois jours, il ne reste que le plus récent ; « tout marquer comme lu ».
        val later = NoticeLog.prune(log, 4 * day)
        assertEquals(listOf("recipes:a"), later.map { it.key })
        assertEquals(0, NoticeLog.unread(NoticeLog.markRead(later)))
        // Vingt au plus.
        var many = emptyList<NoticeEvent>()
        for (i in 0 until 30) many = NoticeLog.add(many, NoticeEvent("k$i", "x", emptyList(), i.toLong()), i.toLong())
        assertEquals(NoticeLog.MAX, many.size)
        assertEquals("k29", many.first().key)
    }

    @Test fun feedChanges() {
        assertEquals(listOf("c"), FeedChanges.added(setOf("a", "b"), listOf("a", "c", "b")))
        val known = listOf(FoyerPlan("2026-11-24", "19:30", "Crêpes", null), FoyerPlan("2026-11-26", "19:30", "Soupe", null))
        val current = listOf(
            FoyerPlan("2026-11-24", "19:30", "Crêpes", null),
            FoyerPlan("2026-11-26", "19:30", "Velouté de potiron", null),
            FoyerPlan("2026-11-23", "20:00", "Hier", null),
        )
        val changed = FeedChanges.changedPlans(known, current, "2026-11-24")
        assertEquals(listOf("Velouté de potiron"), changed.map { it.title })
        assertEquals(emptyList<FoyerPlan>(), FeedChanges.changedPlans(current, current, "2026-11-24"))
    }

    // ------------------------------------------------------------------ recettes

    @Test fun recipesToTryFilter() {
        val idx = SearchIndex(listOf(
            Recipe("foyer-1", "Dahl de lentilles", tags = listOf("À tester"), source = RecipeSource.FOYER),
            Recipe("foyer-2", "Tarte poireaux", tags = listOf("Favori"), source = RecipeSource.FOYER),
            Recipe("lidl-3", "Risotto"),
        ))
        assertEquals(listOf("foyer-1"), idx.search("", RecipeFilter(tag = "À tester")).map { it.entry.id })
        assertEquals(listOf("foyer-1"), idx.search("dahl", RecipeFilter(tag = "À tester", source = RecipeSource.FOYER)).map { it.entry.id })
        assertEquals(3, idx.search("", RecipeFilter()).size)
    }
}
