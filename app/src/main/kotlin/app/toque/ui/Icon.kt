package app.toque.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import app.toque.core.ui.SvgPath

/**
 * Icônes au trait (grille 24×24, dessins Lucide, licence ISC, et quelques-uns faits pour Toque),
 * décrites par leurs tracés SVG, lus une fois par [SvgPath] et rejoués sur le canevas :
 * nettes à toute taille, sans fichier de ressources.
 */
class Icon(context: Context, var kind: Kind, color: Int) : View(context) {
    enum class Kind {
        BACK, ARROW_LEFT, SEARCH, PLAY, PAUSE, STOP, THERMO, SPEED, CLOCK, REVERSE, SCALE, BOOK, STAR, STAR_FILLED,
        SETTINGS, PLUS, MINUS, CHECK, CLOSE, KNEAD, STEAM, FLAME, BOLT, SYNC, LID, WARNING, HOME, WHISK,
        CHEVRON, LINK, INFO, POT, TIMER, BELL, MOON, SUN, GRID, EGG, RICE, KETTLE, BLENDER, CUP, DROPLETS, SOUP,
        PHONE, VIDEO, CALENDAR, SPARKLE, DOWNLOAD, IMAGE, CHAIN, CHEF, AGENT,
    }

    var color: Int = color
        set(v) {
            field = v
            stroke.color = v
            fill.color = v
            invalidate()
        }

    /** Épaisseur du trait, en unités de la grille 24×24. */
    var weight: Float = 1.9f
        set(v) {
            field = v
            invalidate()
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun set(k: Kind) {
        if (k != kind) {
            kind = k
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom) / 24f
        if (s <= 0f) return
        canvas.save()
        canvas.translate(paddingLeft + (width - paddingLeft - paddingRight - 24 * s) / 2f, paddingTop + (height - paddingTop - paddingBottom - 24 * s) / 2f)
        canvas.scale(s, s)
        // Les petites icônes gagnent un peu de trait pour rester lisibles.
        stroke.strokeWidth = if (24 * s < Ui.uf(20f)) weight + 0.3f else weight
        for (shape in shapes(kind)) canvas.drawPath(shape.path, if (shape.filled) fill else stroke)
        canvas.restore()
    }

    private class Shape(val path: Path, val filled: Boolean)

    companion object {
        private val cache = HashMap<Kind, List<Shape>>()

        private fun shapes(k: Kind): List<Shape> = cache[k] ?: build(k).also { cache[k] = it }

        private fun path(d: String): Path {
            val p = Path()
            for (op in SvgPath.parse(d)) when (op) {
                is SvgPath.Op.Move -> p.moveTo(op.x, op.y)
                is SvgPath.Op.Line -> p.lineTo(op.x, op.y)
                is SvgPath.Op.Cubic -> p.cubicTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
                SvgPath.Op.Close -> p.close()
            }
            return p
        }

        private fun c(cx: Float, cy: Float, r: Float) = SvgPath.circle(cx, cy, r)
        private fun r(x: Float, y: Float, w: Float, h: Float, rx: Float = 0f) = SvgPath.rect(x, y, w, h, rx)

        private fun build(k: Kind): List<Shape> {
            val strokes = ArrayList<String>()
            val fills = ArrayList<String>()
            when (k) {
                Kind.BACK -> strokes += "m15 18-6-6 6-6"
                Kind.ARROW_LEFT -> strokes += listOf("m12 19-7-7 7-7", "M19 12H5")
                Kind.CHEVRON -> strokes += "m9 18 6-6-6-6"
                Kind.SEARCH -> strokes += listOf(c(11f, 11f, 8f), "m21 21-4.3-4.3")
                Kind.PLAY -> fills += "M7 4.5v15a1 1 0 0 0 1.5.86l12.5-7.5a1 1 0 0 0 0-1.72L8.5 3.64A1 1 0 0 0 7 4.5Z"
                Kind.PAUSE -> fills += listOf(r(6f, 4f, 4f, 16f, 1f), r(14f, 4f, 4f, 16f, 1f))
                Kind.STOP -> fills += r(5f, 5f, 14f, 14f, 2.5f)
                Kind.THERMO -> strokes += "M14 4v10.54a4 4 0 1 1-4 0V4a2 2 0 0 1 4 0Z"
                Kind.SPEED -> strokes += listOf("m12 14 4-4", "M3.34 19a10 10 0 1 1 17.32 0")
                Kind.CLOCK -> strokes += listOf(c(12f, 13f, 8f), "M12 9v4l2.5 1.5", "M10 2h4")
                Kind.TIMER -> strokes += listOf("M10 2h4", "M12 14l3-3", c(12f, 14f, 8f))
                Kind.REVERSE -> strokes += listOf("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")
                Kind.SCALE -> strokes += listOf(c(12f, 5f, 3f), "M6.5 8a2 2 0 0 0-1.9 1.46L2.1 18.5A2 2 0 0 0 4 21h16a2 2 0 0 0 1.9-2.54L19.4 9.5A2 2 0 0 0 17.5 8Z")
                Kind.BOOK -> strokes += listOf("M2 4h6a4 4 0 0 1 4 4v13a3 3 0 0 0-3-3H2z", "M22 4h-6a4 4 0 0 0-4 4v13a3 3 0 0 1 3-3h7z")
                Kind.STAR -> strokes += STAR
                Kind.STAR_FILLED -> fills += STAR
                Kind.SETTINGS -> strokes += listOf("M20 7h-9", "M14 17H5", c(17f, 17f, 3f), c(7f, 7f, 3f))
                Kind.PLUS -> strokes += listOf("M5 12h14", "M12 5v14")
                Kind.MINUS -> strokes += "M5 12h14"
                Kind.CHECK -> strokes += "M20 6 9 17l-5-5"
                Kind.CLOSE -> strokes += listOf("M18 6 6 18", "m6 6 12 12")
                Kind.KNEAD -> strokes += listOf(
                    "M2 22 16 8",
                    "M3.47 12.53 5 11l1.53 1.53a3.5 3.5 0 0 1 0 4.94L5 19l-1.53-1.53a3.5 3.5 0 0 1 0-4.94Z",
                    "M7.47 8.53 9 7l1.53 1.53a3.5 3.5 0 0 1 0 4.94L9 15l-1.53-1.53a3.5 3.5 0 0 1 0-4.94Z",
                    "M11.47 4.53 13 3l1.53 1.53a3.5 3.5 0 0 1 0 4.94L13 11l-1.53-1.53a3.5 3.5 0 0 1 0-4.94Z",
                    "M20 2h2v2a4 4 0 0 1-4 4h-2V6a4 4 0 0 1 4-4Z",
                )
                Kind.STEAM -> strokes += listOf(
                    "M8 8.5c-1-1.5 1-2.5 0-4", "M12 8.5c-1-1.5 1-2.5 0-4", "M16 8.5c-1-1.5 1-2.5 0-4",
                    "M3 12h18", "M5 12v6a3 3 0 0 0 3 3h8a3 3 0 0 0 3-3v-6",
                )
                Kind.FLAME -> strokes += "M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z"
                Kind.BOLT -> fills += "M13 2 3 14h9l-1 8 10-12h-9l1-8z"
                Kind.SYNC -> strokes += listOf(
                    "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5",
                    "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5",
                )
                Kind.LID -> strokes += listOf("M3 13h18v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M4 9.5 19 5.5", "M10.6 7.8 10.2 6.3")
                Kind.WARNING -> strokes += listOf("m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01")
                Kind.HOME -> strokes += "M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z"
                Kind.WHISK -> strokes += listOf(
                    "M12 21.5V15",
                    "M12 15c-3 0-5-3.5-5-7s2-5.5 5-5.5 5 2 5 5.5-2 7-5 7Z",
                    "M12 15c-1.3 0-2.5-3.5-2.5-7S10.7 2.5 12 2.5 14.5 4.5 14.5 8 13.3 15 12 15Z",
                )
                Kind.LINK -> { strokes += c(12f, 12f, 8.5f); fills += c(12f, 12f, 4f) }
                Kind.INFO -> strokes += listOf(c(12f, 12f, 10f), "M12 16v-4", "M12 8h.01")
                Kind.POT -> strokes += listOf(
                    "M2 12h20", "M20 12v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8", "m4 8 16-4",
                    "m8.86 6.78-.45-1.81a2 2 0 0 1 1.45-2.43l1.94-.48a2 2 0 0 1 2.43 1.46l.45 1.8",
                )
                Kind.BELL -> strokes += listOf("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
                Kind.MOON -> strokes += "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"
                Kind.SUN -> strokes += listOf(
                    c(12f, 12f, 4f),
                    "M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41",
                )
                Kind.GRID -> strokes += listOf(r(3f, 3f, 7f, 7f, 1.5f), r(14f, 3f, 7f, 7f, 1.5f), r(3f, 14f, 7f, 7f, 1.5f), r(14f, 14f, 7f, 7f, 1.5f))
                Kind.EGG -> strokes += "M12 22c6.23-.05 7.87-5.57 7.5-10-.36-4.34-3.95-9.96-7.5-10-3.55.04-7.14 5.66-7.5 10-.37 4.43 1.27 9.95 7.5 10z"
                Kind.RICE -> strokes += listOf(
                    "M3 12h18a9 9 0 0 1-18 0Z", "M7 21h10", "M6.5 12c.4-2.8 2.8-5 5.5-5s5.1 2.2 5.5 5",
                    "M10 9.5h.01M13 8.8h.01M15 10.5h.01M9 11h.01M12 11h.01",
                )
                Kind.KETTLE -> strokes += listOf("M5 21h14", "M7 21v-8a5 5 0 0 1 10 0v8", "M17 13.5 20.5 10", "M10 5h4", "M12 5v3")
                Kind.BLENDER -> strokes += listOf("M7 3h10l-1.5 11h-7z", "M8.5 14h7l1 3h-9z", r(6.5f, 17f, 11f, 4f, 1.2f), "M10 7.5l4 2")
                Kind.CUP -> strokes += listOf(
                    "m6 8 1.75 12.28a2 2 0 0 0 2 1.72h4.54a2 2 0 0 0 2-1.72L18 8", "M5 8h14",
                    "M7 15a6.47 6.47 0 0 1 5 0 6.47 6.47 0 0 0 5 0", "m12 8 1-6h2",
                )
                Kind.DROPLETS -> strokes += listOf(
                    "M7 16.3c2.2 0 4-1.83 4-4.05 0-1.16-.57-2.26-1.71-3.19S7.29 6.75 7 5.3c-.29 1.45-1.14 2.84-2.29 3.76S3 11.1 3 12.25c0 2.22 1.8 4.05 4 4.05z",
                    "M12.56 6.6A10.97 10.97 0 0 0 14 3.02c.5 2.5 2 4.9 4 6.5s3 3.5 3 5.5a6.98 6.98 0 0 1-11.91 4.97",
                )
                Kind.SOUP -> strokes += listOf(
                    "M12 21a9 9 0 0 0 9-9H3a9 9 0 0 0 9 9Z", "M7 21h10", "M19.5 12 22 6",
                    "M16.25 3c.27.1.8.53.75 1.36-.06.83-.93 1.2-1 2.02-.05.78.34 1.24.73 1.62",
                    "M11.25 3c.27.1.8.53.74 1.36-.05.83-.93 1.2-.98 2.02-.06.78.33 1.24.72 1.62",
                    "M6.25 3c.27.1.8.53.75 1.36-.06.83-.93 1.2-1 2.02-.05.78.34 1.24.74 1.62",
                )
                Kind.PHONE -> strokes += listOf(r(6f, 2f, 12f, 20f, 2.5f), "M11 18h2")
                Kind.VIDEO -> strokes += listOf("m16 13 5.2 3.5a.5.5 0 0 0 .8-.4V7.9a.5.5 0 0 0-.75-.43L16 10.5", r(2f, 6f, 14f, 12f, 2f))
                Kind.CALENDAR -> strokes += listOf(r(3f, 4f, 18f, 17f, 2.5f), "M16 2v4M8 2v4M3 10h18")
                Kind.SPARKLE -> strokes += listOf("M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z", "M19 16v5M16.5 18.5h5")
                Kind.DOWNLOAD -> strokes += listOf("M12 3v12", "m7 10 5 5 5-5", "M5 21h14")
                Kind.IMAGE -> strokes += listOf(r(3f, 4f, 18f, 16f, 2.5f), c(9f, 10f, 2f), "m21 16-4.5-4.5L7 20")
                Kind.CHAIN -> strokes += listOf("M9 17H7A5 5 0 0 1 7 7h2", "M15 7h2a5 5 0 1 1 0 10h-2", "M8 12h8")
                // Une invite de terminal : les agents (Claude Code, Codex, Hermès…).
                Kind.AGENT -> strokes += listOf(r(3f, 4.5f, 18f, 15f, 3f), "m7.5 9.5 3 2.5-3 2.5", "M13 15h3.5")
                Kind.CHEF -> strokes += listOf(
                    "M17 21a1 1 0 0 0 1-1v-5.35c0-.457.316-.844.727-1.041a4 4 0 0 0-2.134-7.589 5 5 0 0 0-9.186 0 4 4 0 0 0-2.134 7.588c.411.198.727.585.727 1.041V20a1 1 0 0 0 1 1Z",
                    "M6 17h12",
                )
            }
            return strokes.map { Shape(path(it), false) } + fills.map { Shape(path(it), true) }
        }

        private const val STAR = "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"
    }
}
