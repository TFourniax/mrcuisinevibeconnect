package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.machine.Accessory
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobState
import app.toque.core.machine.ProgramCatalog
import app.toque.core.machine.ProgramDef
import app.toque.core.machine.Snapshot
import app.toque.ui.HoldButton
import app.toque.ui.Icon
import app.toque.ui.Knob
import app.toque.ui.KnobAdjustable
import app.toque.ui.Live
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Section
import app.toque.ui.Ui

/**
 * Les dix programmes (ProgramCatalog), en cercles comme sur le Monsieur Cuisine Smart : on en
 * choisit un, son détail s'affiche à droite (à préparer, réglages, choix, durée), Démarrer. Le
 * Turbo (appui maintenu) reste en bas du détail. Toutes les règles de sécurité s'appliquent.
 */
class ProgramsScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Programmes")
    override val section: Section get() = Section.PROGRAMS

    private val defs = ProgramCatalog.all
    private var selected = defs.indexOfFirst { it.kind == pending }.takeIf { it >= 0 } ?: 0
    /** Choix et durée retenus pour chaque programme (gardés en passant de l'un à l'autre). */
    private val choice = IntArray(defs.size) { defs[it].defaultChoice }
    private val seconds = IntArray(defs.size) { defs[it].startSeconds(defs[it].defaultChoice) }

    private val tiles = ArrayList<LinearLayout>()
    private lateinit var detail: LinearLayout
    private var startBtn: LinearLayout? = null
    private var durationView: TextView? = null
    private lateinit var turbo: HoldButton

    init {
        pending = null
    }

    override fun build(): View {
        tiles.clear()
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(4), Ui.u(24), Ui.u(4))

        // ---- la grille : 5 × 2
        val grid = Knob.region(Ui.vertical(c))
        for (r in 0 until 2) {
            val line = Ui.horizontal(c)
            for (k in 0 until 5) {
                val i = r * 5 + k
                val def = defs.getOrNull(i) ?: continue
                val tile = tile(def, i)
                tiles += tile
                line.addView(tile, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (k < 4) rightMargin = Ui.u(8) })
            }
            grid.addView(line, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { if (r == 0) bottomMargin = Ui.u(12) })
        }
        root.addView(grid, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { rightMargin = Ui.u(24) })

        // ---- le détail
        detail = Knob.region(Ui.vertical(c))
        detail.background = Ui.cardBg(24f)
        Ui.lift(detail)
        detail.setPadding(Ui.u(22), Ui.u(18), Ui.u(22), Ui.u(18))
        root.addView(detail, LinearLayout.LayoutParams(Ui.u(352), Ui.MATCH))
        turbo = HoldButton(c, tr("Turbo : maintenir appuyé"), onDown = {
            a.machine.turboDown(Accessory.BLADE) { v -> if (!v.ok) a.refusal(v) }
        }, onUp = { a.machine.turboUp() })
        refresh()
        return root
    }

    private fun tile(def: ProgramDef, i: Int): LinearLayout {
        val t = Ui.vertical(c, Gravity.CENTER)
        t.setPadding(Ui.u(4), Ui.u(8), Ui.u(4), Ui.u(8))
        val circle = FrameLayout(c)
        circle.addView(Icon(c, Live.kindIcon(def.kind), Ui.ACCENT).apply { weight = 1.6f }, Ui.flp(Ui.u(38), Ui.u(38), Gravity.CENTER))
        t.addView(circle, LinearLayout.LayoutParams(Ui.u(84), Ui.u(84)))
        t.addView(Ui.fitWords(Ui.text(c, def.label, 15f, Ui.TEXT, Ui.SEMIBOLD).apply {
            // Deux lignes réservées pour tous : « Chauffer l'eau » sur deux lignes ne décale plus son icône.
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.05f); minLines = 2
        }, 12f),
            Ui.lp(Ui.MATCH, Ui.WRAP, t = 10))
        t.addView(Ui.ellipsize(Ui.text(c, def.note, 13f, Ui.TEXT2).apply { gravity = Gravity.CENTER }), Ui.lp(Ui.MATCH, Ui.WRAP, t = 3))
        t.isClickable = true
        t.contentDescription = def.label
        t.setOnClickListener {
            selected = i
            refresh()
        }
        return t
    }

    private fun refresh() {
        for ((i, t) in tiles.withIndex()) {
            val on = i == selected
            t.background = Ui.pressable(if (on) Ui.cardBg(22f).apply { setStroke(Ui.u(2f), Ui.ACCENT) } else Ui.round(0, 22f), 22f)
            if (on) Ui.lift(t, 2f) else t.elevation = 0f
            val circle = t.getChildAt(0) as FrameLayout
            circle.background = Ui.round(if (on) Ui.ACCENT else Ui.ACCENT_DIM, 42f)
            (circle.getChildAt(0) as Icon).color = if (on) Ui.ON_ACCENT else Ui.ACCENT
            (t.getChildAt(1) as TextView).setTextColor(if (on) Ui.ACCENT else Ui.TEXT)
        }
        renderDetail()
    }

    private fun renderDetail() {
        val def = defs[selected]
        detail.removeAllViews()
        durationView = null
        val head = Ui.horizontal(c)
        head.addView(Ui.iconCircle(c, Live.kindIcon(def.kind), 44, 22, Ui.ACCENT, Ui.ON_ACCENT),
            LinearLayout.LayoutParams(Ui.u(44), Ui.u(44)).apply { rightMargin = Ui.u(14) })
        head.addView(Ui.fitWords(Ui.text(c, def.label, 25f, Ui.TEXT, Ui.SEMIBOLD), 18f), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        detail.addView(head)
        detail.addView(Ui.ellipsize(Ui.text(c, def.prep, 14f, Ui.TEXT2).apply { setLineSpacing(0f, 1.25f) }, 3), Ui.lp(t = 10))

        val spec = def.job(seconds[selected], choice[selected])
        val chips = Ui.horizontal(c)
        for ((i, label) in ProgramCatalog.settings(spec).withIndex()) {
            val hot = i == 0 && label.contains("°C")
            val icon = when {
                hot -> Icon.Kind.THERMO
                label.contains("→") || label.startsWith(tr("Vitesse")) -> Icon.Kind.SPEED
                label == tr("Inverse") || label == tr("Sens alterné") -> Icon.Kind.REVERSE
                else -> null
            }
            chips.addView(Ui.chip(c, label, icon, if (hot) Ui.ACCENT else if (icon == Icon.Kind.REVERSE) Ui.COOL else Ui.TEXT,
                if (hot) Ui.ACCENT_DIM else Ui.SURFACE3, 13f), Ui.lp(Ui.WRAP, Ui.WRAP, r = 6))
        }
        detail.addView(chips, Ui.lp(t = 10))

        if (def.choices.isNotEmpty()) {
            detail.addView(Ui.overline(c, tr("Cuisson").takeIf { def.kind == JobKind.EGGS } ?: tr("Choix")), Ui.lp(t = 14))
            val row = Ui.horizontal(c)
            for ((i, ch) in def.choices.withIndex()) {
                val on = i == choice[selected]
                val b = Ui.vertical(c, Gravity.CENTER)
                b.background = Ui.pressable(if (on) Ui.round(Ui.ACCENT_DIM, 16f, Ui.ACCENT, 2f) else Ui.round(Ui.SURFACE3, 16f), 16f)
                b.addView(Ui.fitWords(Ui.text(c, ch.label, 13f, if (on) Ui.ACCENT else Ui.TEXT, Ui.SEMIBOLD).apply { gravity = Gravity.CENTER }, 11f),
                    Ui.lp(Ui.MATCH, Ui.WRAP))
                if (ch.seconds > 0) {
                    b.addView(Ui.tabular(Ui.text(c, Ui.duration(ch.seconds), 19f, Ui.TEXT, Ui.LIGHT)).apply { gravity = Gravity.CENTER },
                        Ui.lp(Ui.MATCH, Ui.WRAP, t = 3))
                }
                b.setPadding(Ui.u(4), 0, Ui.u(4), 0)
                b.isClickable = true
                b.setOnClickListener {
                    choice[selected] = i
                    seconds[selected] = def.startSeconds(i)
                    renderDetail()
                    onSnapshot(a.snapshot)
                }
                row.addView(b, LinearLayout.LayoutParams(0, Ui.u(if (def.choices.any { it.seconds > 0 }) 64 else 48), 1f).apply {
                    if (i < def.choices.size - 1) rightMargin = Ui.u(6)
                })
            }
            detail.addView(row, Ui.lp(t = 8))
        }
        if (def.hint.isNotEmpty()) detail.addView(Ui.ellipsize(Ui.text(c, def.hint, 13f, Ui.TEXT2).apply { setLineSpacing(0f, 1.2f) }, 2), Ui.lp(t = 8))

        if (def.adjustable) {
            if (def.choices.isEmpty()) detail.addView(Ui.overline(c, tr("Durée · %s à %s", Ui.duration(def.minSeconds), Ui.duration(def.maxSeconds))), Ui.lp(t = 14))
            val row = Ui.horizontal(c)
            row.addView(Knob.skip(Ui.iconButton(c, Icon.Kind.MINUS, tr("Moins"), Ui.Style.SECONDARY, 52, 22) { bump(-1) }),
                LinearLayout.LayoutParams(Ui.u(52), Ui.u(52)))
            val value = Ui.tabular(Ui.text(c, "", 32f, Ui.TEXT, Ui.LIGHT)).apply { gravity = Gravity.CENTER }
            Knob.adjustable(value, object : KnobAdjustable {
                override fun knobAdjust(delta: Int) = bump(delta)
            })
            durationView = value
            row.addView(value, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            row.addView(Knob.skip(Ui.iconButton(c, Icon.Kind.PLUS, tr("Plus"), Ui.Style.SECONDARY, 52, 22) { bump(1) }),
                LinearLayout.LayoutParams(Ui.u(52), Ui.u(52)))
            detail.addView(row, Ui.lp(t = if (def.choices.isEmpty()) 8 else 10))
            refreshDuration()
        }

        detail.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        val start = Ui.button(c, tr("Démarrer"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 58, textSize = 19f) {
            a.startJob(defs[selected].job(seconds[selected], choice[selected]))
        }
        startBtn = start
        detail.addView(start, Ui.lp(Ui.MATCH, Ui.WRAP))
        (turbo.parent as? LinearLayout)?.removeView(turbo)
        // Turbo à côté des programmes où la lame tourne librement ; pas avec les œufs ou le riz dans
        // le panier, la vapeur ou l'eau à chauffer (et la place sert aux choix). Toujours dans Cuisson.
        if (def.kind !in NO_TURBO) detail.addView(turbo, Ui.lp(Ui.MATCH, Ui.u(48), t = 8))
    }

    private fun bump(dir: Int) {
        val def = defs[selected]
        seconds[selected] = def.step(seconds[selected], dir)
        refreshDuration()
    }

    private fun refreshDuration() {
        durationView?.text = Ui.duration(seconds[selected])
    }

    override fun onSnapshot(s: Snapshot) {
        startBtn?.let { Ui.setButtonEnabled(it, !s.busy && s.state != JobState.PAUSED) }
        turbo.isEnabled = !s.busy || s.turboActive
        turbo.alpha = if (turbo.isEnabled) 1f else 0.35f
    }

    override fun onShow() = applyPending()

    /** Arrivé depuis « Voir » ou un raccourci de l'accueil : ce programme-là. */
    fun applyPending() {
        val want = pending
        pending = null
        if (want != null) {
            val i = defs.indexOfFirst { it.kind == want }
            if (i >= 0 && i != selected) {
                selected = i
                refresh()
            }
        }
    }

    /** Au bouton rotatif : on arrive sur la durée du programme choisi ; Démarrer se touche comme du doigt. */
    override val knobStart: View? get() = durationView ?: startBtn

    companion object {
        private val NO_TURBO = setOf(JobKind.STEAM, JobKind.EGGS, JobKind.RICE, JobKind.WATER)

        /** Programme à montrer au prochain affichage de l'écran. */
        private var pending: JobKind? = null

        fun select(kind: JobKind?) {
            pending = kind?.takeIf { k -> ProgramCatalog.of(k) != null }
        }
    }
}
