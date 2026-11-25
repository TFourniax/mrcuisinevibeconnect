package app.toque.core.ui

/**
 * Lit l'attribut `d` d'un tracé SVG (icônes Lucide, grille 24×24) et le réduit à quatre
 * commandes absolues, faciles à rejouer sur un `android.graphics.Path` d'Android 6 :
 * déplacer, ligne, courbe de Bézier cubique, fermer. Les arcs et les courbes quadratiques sont
 * convertis en cubiques. Sans Android : testable sur la JVM.
 */
object SvgPath {
    sealed class Op {
        data class Move(val x: Float, val y: Float) : Op()
        data class Line(val x: Float, val y: Float) : Op()
        data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Op()
        object Close : Op()
    }

    fun parse(d: String): List<Op> {
        val r = Reader(d)
        val out = ArrayList<Op>()
        var x = 0f
        var y = 0f
        var sx = 0f
        var sy = 0f
        // Dernier point de contrôle (pour S et T), valable si la commande précédente s'y prête.
        var cx = 0f
        var cy = 0f
        var lastCubic = false
        var lastQuad = false
        var qx = 0f
        var qy = 0f
        var cmd = ' '
        while (true) {
            r.skipSeparators()
            if (r.done) break
            val c = r.peek()
            if (c.isLetter()) {
                cmd = c
                r.next()
            } else if (cmd == ' ') {
                throw IllegalArgumentException("tracé sans commande : $d")
            }
            val rel = cmd.isLowerCase()
            val ox = if (rel) x else 0f
            val oy = if (rel) y else 0f
            when (cmd.uppercaseChar()) {
                'M' -> {
                    x = ox + r.number(); y = oy + r.number()
                    out += Op.Move(x, y)
                    sx = x; sy = y
                    // Les paires suivantes d'un M sont des lignes.
                    cmd = if (rel) 'l' else 'L'
                    lastCubic = false; lastQuad = false
                }
                'L' -> {
                    x = ox + r.number(); y = oy + r.number()
                    out += Op.Line(x, y)
                    lastCubic = false; lastQuad = false
                }
                'H' -> {
                    x = ox + r.number()
                    out += Op.Line(x, y)
                    lastCubic = false; lastQuad = false
                }
                'V' -> {
                    y = oy + r.number()
                    out += Op.Line(x, y)
                    lastCubic = false; lastQuad = false
                }
                'C' -> {
                    val x1 = ox + r.number(); val y1 = oy + r.number()
                    val x2 = ox + r.number(); val y2 = oy + r.number()
                    x = ox + r.number(); y = oy + r.number()
                    out += Op.Cubic(x1, y1, x2, y2, x, y)
                    cx = x2; cy = y2; lastCubic = true; lastQuad = false
                }
                'S' -> {
                    val x1 = if (lastCubic) 2 * x - cx else x
                    val y1 = if (lastCubic) 2 * y - cy else y
                    val x2 = ox + r.number(); val y2 = oy + r.number()
                    x = ox + r.number(); y = oy + r.number()
                    out += Op.Cubic(x1, y1, x2, y2, x, y)
                    cx = x2; cy = y2; lastCubic = true; lastQuad = false
                }
                'Q' -> {
                    val x1 = ox + r.number(); val y1 = oy + r.number()
                    val ex = ox + r.number(); val ey = oy + r.number()
                    out += quad(x, y, x1, y1, ex, ey)
                    x = ex; y = ey; qx = x1; qy = y1; lastQuad = true; lastCubic = false
                }
                'T' -> {
                    val x1 = if (lastQuad) 2 * x - qx else x
                    val y1 = if (lastQuad) 2 * y - qy else y
                    val ex = ox + r.number(); val ey = oy + r.number()
                    out += quad(x, y, x1, y1, ex, ey)
                    x = ex; y = ey; qx = x1; qy = y1; lastQuad = true; lastCubic = false
                }
                'A' -> {
                    val rx = r.number(); val ry = r.number(); val rot = r.number()
                    val large = r.flag(); val sweep = r.flag()
                    val ex = ox + r.number(); val ey = oy + r.number()
                    arc(out, x, y, rx, ry, rot, large, sweep, ex, ey)
                    x = ex; y = ey
                    lastCubic = false; lastQuad = false
                }
                'Z' -> {
                    out += Op.Close
                    x = sx; y = sy
                    lastCubic = false; lastQuad = false
                }
                else -> throw IllegalArgumentException("commande inconnue « $cmd » : $d")
            }
        }
        return out
    }

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x: Float, y: Float) = Op.Cubic(
        x0 + 2f / 3f * (x1 - x0), y0 + 2f / 3f * (y1 - y0),
        x + 2f / 3f * (x1 - x), y + 2f / 3f * (y1 - y),
        x, y,
    )

    /** Arc elliptique (notation SVG « endpoint ») en courbes cubiques de 90° au plus. */
    private fun arc(out: MutableList<Op>, x1: Float, y1: Float, rxIn: Float, ryIn: Float, rotDeg: Float,
                    large: Boolean, sweep: Boolean, x2: Float, y2: Float) {
        if (x1 == x2 && y1 == y2) return
        var rx = Math.abs(rxIn.toDouble())
        var ry = Math.abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) {
            out += Op.Line(x2, y2)
            return
        }
        val phi = Math.toRadians(rotDeg.toDouble())
        val cosPhi = Math.cos(phi)
        val sinPhi = Math.sin(phi)
        val dx = (x1 - x2) / 2.0
        val dy = (y1 - y2) / 2.0
        val x1p = cosPhi * dx + sinPhi * dy
        val y1p = -sinPhi * dx + cosPhi * dy
        // Rayons trop petits : agrandis juste assez (règle SVG).
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) {
            val s = Math.sqrt(lambda)
            rx *= s
            ry *= s
        }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = if (den == 0.0) 0.0 else Math.sqrt(Math.max(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * -(ry * x1p / rx)
        val cxa = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2.0
        val cya = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2.0
        val theta1 = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && delta > 0) delta -= 2 * Math.PI
        else if (sweep && delta < 0) delta += 2 * Math.PI
        val segments = Math.ceil(Math.abs(delta) / (Math.PI / 2) - 1e-9).toInt().coerceAtLeast(1)
        val step = delta / segments
        val k = 4.0 / 3.0 * Math.tan(step / 4)
        var t = theta1
        for (i in 0 until segments) {
            val t2 = t + step
            val cos1 = Math.cos(t); val sin1 = Math.sin(t)
            val cos2 = Math.cos(t2); val sin2 = Math.sin(t2)
            // Points sur le cercle unité, puis mis à l'échelle, tournés et déplacés.
            fun px(u: Double, v: Double) = (cosPhi * rx * u - sinPhi * ry * v + cxa).toFloat()
            fun py(u: Double, v: Double) = (sinPhi * rx * u + cosPhi * ry * v + cya).toFloat()
            val ex = if (i == segments - 1) x2 else px(cos2, sin2)
            val ey = if (i == segments - 1) y2 else py(cos2, sin2)
            out += Op.Cubic(
                px(cos1 - k * sin1, sin1 + k * cos1), py(cos1 - k * sin1, sin1 + k * cos1),
                px(cos2 + k * sin2, sin2 - k * cos2), py(cos2 + k * sin2, sin2 - k * cos2),
                ex, ey,
            )
            t = t2
        }
    }

    private fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val dot = ux * vx + uy * vy
        val len = Math.sqrt(ux * ux + uy * uy) * Math.sqrt(vx * vx + vy * vy)
        var a = Math.acos((dot / len).coerceIn(-1.0, 1.0))
        if (ux * vy - uy * vx < 0) a = -a
        return a
    }

    /** Cercle en tracé (deux arcs), pour décrire une icône d'une seule chaîne. */
    fun circle(cx: Float, cy: Float, r: Float): String =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    /** Rectangle aux coins arrondis [rx] en tracé. */
    fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float = 0f): String {
        if (rx <= 0f) return "M$x ${y}h${w}v${h}h${-w}z"
        val r = minOf(rx, w / 2, h / 2)
        return "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}" +
            "a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"
    }

    private class Reader(private val s: String) {
        private var i = 0
        val done: Boolean get() = i >= s.length
        fun peek(): Char = s[i]
        fun next(): Char = s[i++]

        fun skipSeparators() {
            while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
        }

        /** Un drapeau d'arc : un seul chiffre 0 ou 1, collé ou non au suivant (« a2 2 0 01 3 4 »). */
        fun flag(): Boolean {
            skipSeparators()
            require(i < s.length && (s[i] == '0' || s[i] == '1')) { "drapeau d'arc attendu : $s" }
            return s[i++] == '1'
        }

        fun number(): Float {
            skipSeparators()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length) {
                val ch = s[i]
                if (ch.isDigit()) i++
                else if (ch == '.' && !dot) { dot = true; i++ }
                else break
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            require(i > start) { "nombre attendu à la position $start : $s" }
            return s.substring(start, i).toFloat()
        }
    }
}
