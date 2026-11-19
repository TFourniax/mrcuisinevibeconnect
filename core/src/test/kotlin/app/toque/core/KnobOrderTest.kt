package app.toque.core

import app.toque.core.ui.Box
import app.toque.core.ui.KnobOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class KnobOrderTest {
    private fun box(l: Int, t: Int, w: Int, h: Int) = Box(l, t, l + w, t + h)

    @Test
    fun readsRowsTopToBottomThenLeftToRight() {
        // Cuisson manuelle : trois cartes, puis les puces et le bouton Démarrer en bas à droite.
        val boxes = listOf(
            box(700, 470, 280, 80),  // 0 Démarrer
            box(20, 10, 200, 400),   // 1 Durée
            box(450, 12, 200, 400),  // 2 Vitesse
            box(240, 10, 200, 400),  // 3 Température
            box(20, 480, 120, 50),   // 4 Sens normal
            box(150, 482, 100, 50),  // 5 Inverse
        )
        assertEquals(listOf(1, 3, 2, 4, 5, 0), KnobOrder.reading(boxes))
    }

    @Test
    fun smallButtonBesideATallCardStaysOnItsRow() {
        val boxes = listOf(
            box(0, 0, 300, 400),     // 0 grande carte
            box(320, 300, 100, 60),  // 1 bouton en bas à droite de la carte
            box(0, 420, 300, 60),    // 2 ligne suivante
        )
        // Le bouton chevauche la carte sur toute sa hauteur : même ligne.
        assertEquals(listOf(0, 1, 2), KnobOrder.reading(boxes))
    }

    @Test
    fun slightlyMisalignedItemsShareARow() {
        val boxes = listOf(box(300, 104, 100, 60), box(0, 100, 100, 60), box(150, 98, 100, 60), box(0, 200, 100, 60))
        assertEquals(listOf(1, 2, 0, 3), KnobOrder.reading(boxes))
    }

    @Test
    fun gridOfRecipeCards() {
        val boxes = ArrayList<Box>()
        for (row in 0 until 3) for (col in 0 until 4) boxes += box(col * 250, row * 240, 236, 232)
        // L'ordre de lecture retrouve la grille quel que soit l'ordre de départ.
        val shuffled = boxes.shuffled(java.util.Random(7))
        assertEquals(boxes, KnobOrder.reading(shuffled).map { shuffled[it] })
    }

    @Test
    fun emptyAndSingle() {
        assertEquals(emptyList<Int>(), KnobOrder.reading(emptyList()))
        assertEquals(listOf(0), KnobOrder.reading(listOf(box(0, 0, 10, 10))))
    }

    @Test
    fun stepsWithoutWrapping() {
        assertEquals(0, KnobOrder.step(-1, 5, 1))
        assertEquals(0, KnobOrder.step(-1, 5, -1))
        assertEquals(3, KnobOrder.step(2, 5, 1))
        assertEquals(4, KnobOrder.step(4, 5, 1))
        assertEquals(0, KnobOrder.step(0, 5, -1))
        assertEquals(-1, KnobOrder.step(0, 0, 1))
    }

    @Test
    fun nearestFindsTheClosestCentre() {
        val boxes = listOf(box(0, 0, 100, 50), box(200, 0, 100, 50), box(0, 300, 100, 50))
        assertEquals(1, KnobOrder.nearest(boxes, box(190, 10, 100, 50)))
        assertEquals(2, KnobOrder.nearest(boxes, box(0, 280, 80, 40)))
        assertEquals(-1, KnobOrder.nearest(emptyList(), box(0, 0, 1, 1)))
    }
}
