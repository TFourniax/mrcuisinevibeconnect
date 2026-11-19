package app.toque.core.ui

/** Rectangle à l'écran (pixels), sans dépendance à Android. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * Ordre de parcours au bouton rotatif : l'ordre de lecture, ligne par ligne de haut en bas,
 * puis de gauche à droite dans chaque ligne. Deux éléments sont sur la même ligne quand ils se
 * chevauchent verticalement sur au moins la moitié de la hauteur du plus petit (un bouton à
 * côté d'une grande carte reste sur la ligne de la carte).
 */
object KnobOrder {
    /** Indices de [boxes] dans l'ordre de lecture. */
    fun reading(boxes: List<Box>): List<Int> {
        if (boxes.isEmpty()) return emptyList()
        val byTop = boxes.indices.sortedWith(compareBy<Int>({ boxes[it].top }, { boxes[it].left }))
        val rows = ArrayList<MutableList<Int>>()
        var anchor: Box? = null
        for (i in byTop) {
            val b = boxes[i]
            val a = anchor
            if (a != null && sameRow(a, b)) {
                rows.last() += i
            } else {
                rows += mutableListOf(i)
                anchor = b
            }
        }
        return rows.flatMap { row -> row.sortedWith(compareBy<Int>({ boxes[it].left }, { boxes[it].top })) }
    }

    private fun sameRow(a: Box, b: Box): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        val smaller = minOf(a.height, b.height)
        if (smaller <= 0) return overlap >= 0 && a.top == b.top
        return overlap * 2 >= smaller
    }

    /**
     * Position suivante en tournant de [delta] depuis [current] parmi [size] éléments, sans
     * reboucler : on reste au premier ou au dernier. [current] négatif : rien n'est encore
     * sélectionné, on part du premier.
     */
    fun step(current: Int, size: Int, delta: Int): Int {
        if (size <= 0) return -1
        if (current < 0) return 0
        return (current + delta).coerceIn(0, size - 1)
    }

    /** L'élément dont le centre est le plus proche de celui de [to] (−1 si la liste est vide). */
    fun nearest(boxes: List<Box>, to: Box): Int {
        var best = -1
        var bestD = Long.MAX_VALUE
        for ((i, b) in boxes.withIndex()) {
            val dx = (b.centerX - to.centerX).toLong()
            val dy = (b.centerY - to.centerY).toLong()
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }
}
