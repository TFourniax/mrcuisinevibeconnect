package app.toque.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import app.toque.core.ui.Box
import app.toque.core.ui.KnobOrder
import java.util.WeakHashMap

/**
 * Une vue dont le bouton rotatif change la valeur : un appui entre dans le réglage, tourner
 * appelle [knobAdjust] (±1), un nouvel appui le termine ([knobDone]) ; un toucher de l'écran ou
 * un changement d'écran l'abandonne ([knobCancel]).
 */
interface KnobAdjustable {
    fun knobAdjust(delta: Int)
    /** Entrée dans le réglage (la vue peut se montrer sélectionnée). */
    fun knobBegin() {}
    /** Fin du réglage par un appui : valider ce qui n'est pas encore appliqué. */
    fun knobDone() {}
    /** Fin du réglage par un toucher ou un changement d'écran : revenir à la valeur appliquée. */
    fun knobCancel() {}
}

/** Marques posées par les écrans pour le parcours au bouton rotatif. */
object Knob {
    private val adjust = WeakHashMap<View, KnobAdjustable>()
    private val skipped = WeakHashMap<View, Boolean>()
    private val regions = WeakHashMap<View, Boolean>()

    /** [v] se règle au bouton (sans descendre dans ses enfants). */
    fun <T : View> adjustable(v: T, a: KnobAdjustable): T {
        adjust[v] = a
        return v
    }

    fun <T : View> adjustable(v: T, onAdjust: (Int) -> Unit): T = adjustable(v, object : KnobAdjustable {
        override fun knobAdjust(delta: Int) = onAdjust(delta)
    })

    /** [v] n'est pas une étape du parcours (ses enfants le restent). */
    fun <T : View> skip(v: T): T {
        skipped[v] = true
        return v
    }

    /** Les éléments de [v] (une colonne) se parcourent d'un bloc, avant de passer à la suivante. */
    fun <T : View> region(v: T): T {
        regions[v] = true
        return v
    }

    internal fun adjustOf(v: View): KnobAdjustable? = (v as? KnobAdjustable) ?: adjust[v]
    internal fun isSkipped(v: View): Boolean = skipped[v] == true
    internal fun isRegion(v: View): Boolean = regions[v] == true
}

/** Le cadre lumineux posé sur l'élément choisi au bouton rotatif ; ne capte aucun toucher. */
class FocusRing(c: Context) : View(c) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.ACCENT }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Ui.ACCENT_DIM }

    /** Le thème a changé : le cadre prend l'accent du nouveau. */
    fun restyle() {
        stroke.color = Ui.ACCENT
        fill.color = (Ui.ACCENT and 0x00FFFFFF) or 0x29000000
        invalidate()
    }
    private val r = RectF()
    private var radius = 0f
    var showing = false
        private set
    private var adjusting = false

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** [rect] dans les coordonnées de ce calque. */
    fun place(rect: Rect, adjusting: Boolean) {
        val pad = Ui.uf(5f)
        r.set(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
        radius = minOf(r.height() / 2f, Ui.uf(28f))
        this.adjusting = adjusting
        showing = true
        invalidate()
    }

    fun hide() {
        if (!showing) return
        showing = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!showing) return
        stroke.strokeWidth = Ui.uf(if (adjusting) 5f else 3f)
        if (adjusting) canvas.drawRoundRect(r, radius, radius, fill)
        canvas.drawRoundRect(r, radius, radius, stroke)
    }
}

/**
 * Parcours de l'interface au bouton rotatif, comme l'appli d'origine : tourner passe d'un
 * élément touchable à l'autre dans l'ordre de lecture, appuyer le touche ; sur une valeur
 * réglable (durée, température, vitesse…), un appui entre dans le réglage, tourner la change, un
 * second appui la valide. Le premier geste montre seulement le cadre ; un toucher de l'écran le
 * cache. Les appuis passent par le même chemin qu'un doigt (performClick) : les règles de
 * sécurité et les confirmations s'appliquent à l'identique.
 */
class KnobNav(private val a: MainActivity, private val layer: FrameLayout) {
    private class Target(val view: View, val list: AbsListView? = null, val pos: Int = -1, val adjust: KnobAdjustable? = null) {
        fun same(o: Target?): Boolean = o != null && if (list != null) list === o.list && pos == o.pos else view === o.view
    }

    private sealed class Node(val box: Box)
    private class Leaf(val t: Target, box: Box) : Node(box)
    private class Group(box: Box, val children: List<Node>) : Node(box)

    val ring = FocusRing(a)
    private var focus: Target? = null

    /** Une valeur est en train d'être réglée au bouton (le thème attend la fin). */
    val adjustingNow: Boolean get() = adjusting != null

    /** Le thème a changé : le cadre prend les couleurs du nouveau (il reste au-dessus de l'interface refaite). */
    fun restyle() = ring.restyle()
    private var adjusting: Target? = null
    private var lastBox: Box? = null
    /** Le bouton est en train de servir : le cadre suit les changements d'écran. */
    private var active = false
    /** Défilement d'une liste en cours vers une carte qui n'était pas affichée. */
    private var pending: Target? = null
    private var pendingTries = 0
    private val loc = IntArray(2)
    private val layerLoc = IntArray(2)

    init {
        layer.addView(ring, Ui.flp())
        val follow = { if (ring.showing) place() }
        layer.viewTreeObserver.addOnGlobalLayoutListener { follow() }
        layer.viewTreeObserver.addOnScrollChangedListener { follow() }
    }

    // ------------------------------------------------------------------ gestes

    fun turn(delta: Int) {
        active = true
        adjusting?.let { t ->
            if (alive(t)) {
                t.adjust?.knobAdjust(delta)
                place()
                return
            }
            stopAdjusting(cancel = true)
        }
        pending?.let { p ->
            // Tours rapides pendant le défilement d'une liste : on vise la carte d'après.
            val l = p.list ?: return
            scrollListTo(l, (p.pos + delta).coerceIn(0, maxOf(0, (l.adapter?.count ?: 1) - 1)))
            return
        }
        val targets = collect()
        if (targets.isEmpty()) {
            ring.hide()
            return
        }
        val cur = resolve(targets)
        if (cur < 0) {
            focusOn(start(targets))
            return
        }
        if (!ring.showing) {
            // Premier geste après un toucher : le cadre revient là où il était, sans bouger.
            focusOn(targets[cur])
            return
        }
        val t = targets[cur]
        // Une liste continue au-delà de ses cartes affichées : on la fait défiler.
        val list = t.list
        if (list != null) {
            val want = t.pos + delta
            val count = list.adapter?.count ?: 0
            if (want in 0 until count && targets.none { it.list === list && it.pos == want }) {
                scrollListTo(list, want)
                return
            }
        }
        focusOn(targets[KnobOrder.step(cur, targets.size, delta)])
    }

    fun press() {
        active = true
        adjusting?.let { t ->
            stopAdjusting(cancel = false)
            if (alive(t)) place()
            return
        }
        if (pending != null) return
        val targets = collect()
        val cur = resolve(targets)
        if (cur < 0) {
            if (targets.isNotEmpty()) focusOn(start(targets))
            return
        }
        if (!ring.showing) {
            focusOn(targets[cur])
            return
        }
        val t = targets[cur]
        val adj = t.adjust
        if (adj != null) {
            adjusting = t
            adj.knobBegin()
            place()
            return
        }
        click(t)
    }

    /** Un doigt sur l'écran : le cadre disparaît (la position est gardée pour le prochain tour). */
    fun touch() {
        active = false
        pending = null
        stopAdjusting(cancel = true)
        ring.hide()
    }

    /** Nouvel écran ou nouvelle fenêtre de dialogue : on repart de son élément de départ. */
    fun reset() {
        stopAdjusting(cancel = true)
        focus = null
        lastBox = null
        pending = null
        ring.hide()
        if (!active) return
        layer.postDelayed({ if (active && focus == null) showStart() }, 80)
    }

    private fun showStart() {
        val targets = collect()
        if (targets.isNotEmpty()) focusOn(start(targets))
    }

    private fun stopAdjusting(cancel: Boolean) {
        val t = adjusting ?: return
        adjusting = null
        if (cancel) t.adjust?.knobCancel() else t.adjust?.knobDone()
    }

    private fun click(t: Target) {
        val v = t.view
        v.drawableHotspotChanged(v.width / 2f, v.height / 2f)
        v.isPressed = true
        v.postDelayed({
            v.isPressed = false
            val list = t.list
            if (list != null) {
                if (list.isShown) list.performItemClick(v, t.pos, list.adapter?.getItemId(t.pos) ?: t.pos.toLong())
            } else if (v.isShown && v.isEnabled) {
                v.performClick()
            }
        }, 90)
    }

    // ------------------------------------------------------------------ cible courante

    private fun start(targets: List<Target>): Target {
        if (a.knobModal) return targets[0]
        val pref = a.current?.knobStart
        if (pref != null) targets.firstOrNull { it.view === pref }?.let { return it }
        val content = a.knobContent
        return targets.firstOrNull { isInside(it.view, content) } ?: targets[0]
    }

    /** Position de la cible courante dans [targets] ; une cible disparue est remplacée par la plus proche. */
    private fun resolve(targets: List<Target>): Int {
        val f = focus ?: return -1
        val i = targets.indexOfFirst { it.same(f) }
        if (i >= 0) {
            focus = targets[i]
            return i
        }
        val last = lastBox ?: return -1
        val j = KnobOrder.nearest(targets.map { box(it.view) }, last)
        if (j >= 0) focus = targets[j]
        return j
    }

    private fun focusOn(t: Target) {
        focus = t
        ensureVisible(t)
        place()
    }

    private fun alive(t: Target): Boolean = t.view.isAttachedToWindow && t.view.isShown

    /** Place le cadre sur la cible courante (après un défilement ou une mise en page aussi). */
    private fun place() {
        var t = focus ?: return ring.hide()
        val list = t.list
        if (list != null) {
            // Les cartes d'une liste sont recyclées : on retrouve celle de la position voulue.
            val child = list.getChildAt(t.pos - list.firstVisiblePosition)
            if (child != null && child !== t.view) {
                t = Target(child, list, t.pos)
                focus = t
            }
        }
        if (!alive(t)) {
            if (!active) return ring.hide()
            val targets = collect()
            val i = resolve(targets)
            if (i < 0) return ring.hide()
            t = targets[i]
            if (!alive(t)) return ring.hide()
        }
        val v = t.view
        v.getLocationInWindow(loc)
        layer.getLocationInWindow(layerLoc)
        val x = loc[0] - layerLoc[0]
        val y = loc[1] - layerLoc[1]
        lastBox = Box(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        ring.place(Rect(x, y, x + v.width, y + v.height), adjusting?.same(t) == true)
    }

    private fun ensureVisible(t: Target) {
        val v = t.view
        val pad = Ui.u(10)
        val list = t.list
        if (list != null) {
            val top = list.paddingTop + pad
            val bottom = list.height - list.paddingBottom - pad
            when {
                v.bottom > bottom -> list.smoothScrollBy(v.bottom - bottom, 160)
                v.top < top -> list.smoothScrollBy(v.top - top, 160)
            }
            return
        }
        v.requestRectangleOnScreen(Rect(-pad, -pad, v.width + pad, v.height + pad), false)
    }

    private fun scrollListTo(list: AbsListView, pos: Int) {
        val token = Target(list, list, pos)
        pending = token
        pendingTries = 6
        list.smoothScrollToPosition(pos)
        list.postDelayed(object : Runnable {
            override fun run() {
                if (pending !== token) return
                val child = list.getChildAt(pos - list.firstVisiblePosition)
                if (child != null) {
                    pending = null
                    focusOn(Target(child, list, pos))
                } else if (--pendingTries > 0) {
                    list.postDelayed(this, 120)
                } else {
                    pending = null
                }
            }
        }, 180)
    }

    // ------------------------------------------------------------------ collecte

    /** Les cibles de l'écran, dans l'ordre de parcours. */
    private fun collect(): List<Target> {
        val out = ArrayList<Target>()
        for (area in a.knobAreas()) {
            if (area.visibility != View.VISIBLE || !area.isShown) continue
            val nodes = ArrayList<Node>()
            if (area is ViewGroup) for (i in 0 until area.childCount) walk(area.getChildAt(i), nodes)
            flatten(nodes, out)
        }
        return out
    }

    private fun flatten(nodes: List<Node>, out: MutableList<Target>) {
        for (i in KnobOrder.reading(nodes.map { it.box })) {
            when (val n = nodes[i]) {
                is Leaf -> out += n.t
                is Group -> flatten(n.children, out)
            }
        }
    }

    private fun walk(v: View, into: MutableList<Node>) {
        if (v.visibility != View.VISIBLE || v === ring) return
        val sized = v.width > 0 && v.height > 0
        val adj = Knob.adjustOf(v)
        if (adj != null) {
            if (v.isEnabled && sized && !Knob.isSkipped(v)) into += Leaf(Target(v, adjust = adj), box(v))
            return
        }
        if (v is AbsListView) {
            val items = ArrayList<Node>()
            if (v.onItemClickListener != null) {
                for (i in 0 until v.childCount) {
                    val child = v.getChildAt(i)
                    if (child.visibility == View.VISIBLE && child.height > 0) items += Leaf(Target(child, v, v.firstVisiblePosition + i), box(child))
                }
            }
            if (items.isNotEmpty()) into += Group(box(v), items)
            return
        }
        if (sized && v.isClickable && v.isEnabled && v.hasOnClickListeners() && !Knob.isSkipped(v)) into += Leaf(Target(v), box(v))
        if (v !is ViewGroup) return
        val block = v is ScrollView || v is HorizontalScrollView || Knob.isRegion(v)
        val sink = if (block) ArrayList<Node>() else into
        for (i in 0 until v.childCount) walk(v.getChildAt(i), sink)
        if (!block) return
        when {
            sink.isNotEmpty() -> into += Group(box(v), sink)
            // Un long texte sans bouton (étapes, ingrédients, journal) : un appui, puis tourner pour le faire défiler.
            v is ScrollView && sized && canScroll(v) -> into += Leaf(Target(v, adjust = scroller(v)), box(v))
        }
    }

    private fun canScroll(v: ScrollView): Boolean {
        val child = v.getChildAt(0) ?: return false
        return child.height > v.height - v.paddingTop - v.paddingBottom + Ui.u(4)
    }

    private fun scroller(v: ScrollView) = object : KnobAdjustable {
        override fun knobAdjust(delta: Int) = v.smoothScrollBy(0, delta * maxOf(Ui.u(60), v.height / 3))
    }

    private fun box(v: View): Box {
        v.getLocationInWindow(loc)
        return Box(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    private fun isInside(v: View, ancestor: View): Boolean {
        var p: Any? = v
        while (p is View) {
            if (p === ancestor) return true
            p = p.parent
        }
        return false
    }
}
