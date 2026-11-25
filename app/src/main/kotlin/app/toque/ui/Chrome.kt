package app.toque.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.Prefs
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Mcc
import java.text.SimpleDateFormat
import java.util.Date

/** Les cinq sections de la barre du bas. */
enum class Section(val icon: Icon.Kind) {
    HOME(Icon.Kind.HOME), RECIPES(Icon.Kind.BOOK), MANUAL(Icon.Kind.POT), PROGRAMS(Icon.Kind.GRID), SCALE(Icon.Kind.SCALE);

    val label: String
        get() = when (this) {
            HOME -> tr("Accueil")
            RECIPES -> tr("Recettes")
            MANUAL -> tr("Cuisson")
            PROGRAMS -> tr("Programmes")
            SCALE -> tr("Balance")
        }
}

/** Écran de l'appli (vue construite à la demande, reconstruite quand le thème change). */
abstract class Screen(val a: MainActivity) {
    abstract val title: String
    private var built: View? = null
    val view: View get() = built ?: build().also { built = it }
    val c: Context get() = a

    protected abstract fun build(): View

    /** Le thème a changé : la vue sera refaite au prochain affichage (l'état de l'écran reste). */
    fun dropView() {
        built = null
    }

    open fun onShow() {}
    open fun onHide() {}
    open fun onSnapshot(s: Snapshot) {}
    open fun onEvent(e: MachineEvent) {}
    /**
     * Bouton rotatif géré par l'écran lui-même (true : le parcours général n'a pas lieu). Par
     * défaut, tourner passe d'un élément à l'autre et appuyer le touche (KnobNav).
     */
    open fun onKnob(delta: Int): Boolean = false
    open fun onKnobPress(): Boolean = false
    /** Élément où le cadre du bouton rotatif se pose en arrivant sur l'écran (sinon le premier). */
    open val knobStart: View? get() = null
    open fun canLeave(): Boolean = true
    /** La recette affichée : le panneau vidéo d'une recette reste ouvert sur ses écrans à elle. */
    open val recipeId: String? get() = null
    /** Côté où le panneau vidéo se place sur cet écran (Gravity.START ou END). */
    open val videoSide: Int get() = Gravity.END
    /** Hauteur à laisser libre sous le panneau vidéo (boutons du bas de l'écran), en unités Ui. */
    open val videoBottomSpace: Int get() = 0
    /** Écran d'une section de la barre du bas (sinon un écran de détail, avec retour). */
    open val section: Section? get() = null
    /** Plein écran (recette guidée) : pas de barre du bas, comme le mode magasin de Foyer. */
    open val fullScreen: Boolean get() = false
}

/** Ce qu'on dit d'un programme en cours, dans la barre du bas et sur l'accueil. */
object Live {
    fun kindIcon(kind: JobKind?): Icon.Kind = when (kind) {
        JobKind.KNEAD -> Icon.Kind.KNEAD
        JobKind.STEAM -> Icon.Kind.STEAM
        JobKind.TOAST -> Icon.Kind.FLAME
        JobKind.TURBO -> Icon.Kind.BOLT
        JobKind.SIMMER -> Icon.Kind.SOUP
        JobKind.RICE -> Icon.Kind.RICE
        JobKind.EGGS -> Icon.Kind.EGG
        JobKind.WATER -> Icon.Kind.KETTLE
        JobKind.BLEND -> Icon.Kind.BLENDER
        JobKind.SMOOTHIE -> Icon.Kind.CUP
        JobKind.PREWASH -> Icon.Kind.DROPLETS
        JobKind.MANUAL, null -> Icon.Kind.POT
    }

    /** « En cours · phase 1/2 », « Pause : couvercle ouvert », « Terminé »… */
    fun stateText(s: Snapshot): String {
        if (s.turboActive) return tr("Relâche pour arrêter")
        return when (s.state) {
            JobState.STARTING -> if (s.elapsedMs == 0L) tr("Démarrage…") else tr("Reprise…")
            JobState.RUNNING -> if (s.waitingStop) tr("Changement de sens…") else if (s.phaseCount > 1) tr("En cours · phase %d/%d", s.phaseIndex + 1, s.phaseCount) else tr("En cours")
            JobState.STOPPING -> tr("Arrêt du moteur…")
            JobState.PAUSED -> when (s.pauseReason) {
                "LID" -> tr("Pause : couvercle ouvert")
                "SAFETY" -> tr("Interrompu par sécurité")
                else -> tr("En pause")
            }
            JobState.FINISHED -> tr("Terminé")
            JobState.ABORTED -> s.endReason
            null -> ""
        }
    }

    fun stateColor(s: Snapshot): Int = when (s.state) {
        JobState.PAUSED -> Ui.WARN
        JobState.FINISHED -> Ui.OK
        JobState.ABORTED -> Ui.DANGER
        else -> Ui.TEXT2
    }

    /** « 95 °C · vitesse 1 · inverse », « Vapeur 120 °C ». */
    fun settingsText(s: Snapshot): String {
        val parts = ArrayList<String>(3)
        val t = if (s.jobTempLevel > 0) Mcc.celsiusForLevel(s.jobTempLevel) else 0
        if (s.kind == JobKind.STEAM || s.kind == JobKind.EGGS) parts += tr("Vapeur %d °C", t)
        else {
            if (t > 0) parts += "$t °C"
            if (s.jobSpeed > 0) parts += tr("vitesse %d", s.jobSpeed)
            if (s.jobReverse) parts += tr("inverse")
        }
        return parts.joinToString(" · ")
    }

    /** Chauffer l'eau : on suit la température plutôt qu'un minuteur. */
    fun followsTemperature(s: Snapshot): Boolean = s.kind == JobKind.WATER

    /** Le grand chiffre : temps restant, ou température de l'eau. */
    fun bigValue(s: Snapshot): String = when {
        s.state == JobState.FINISHED -> if (followsTemperature(s)) "${s.actualTempC} °C" else "00:00"
        followsTemperature(s) && s.hasSample -> "${s.actualTempC} °C"
        else -> Ui.clock(s.remainingMs)
    }

    fun progress(s: Snapshot): Float = when {
        s.state == JobState.FINISHED -> 1f
        followsTemperature(s) && s.jobTempLevel > 0 -> (s.actualTempC.toFloat() / Mcc.celsiusForLevel(s.jobTempLevel)).coerceIn(0f, 1f)
        s.totalMs > 0 -> s.elapsedMs.toFloat() / s.totalMs
        else -> 0f
    }
}

/**
 * Barre du haut. À l'accueil : l'heure et la date. Ailleurs : le titre (avec retour sur un écran
 * de détail) et l'heure à droite. Toujours : couvercle, température du bol, état de la base, la
 * lune (ou le soleil) pour changer de thème, la cloche « À savoir » et les réglages.
 */
class TopBar(private val a: MainActivity) : LinearLayout(a) {
    enum class Mode { HOME, SECTION, DETAIL }

    private val back = Ui.iconButton(a, Icon.Kind.ARROW_LEFT, tr("Retour"), Ui.Style.GHOST, size = 48, iconSize = 24) { a.back() }
    private val homeBox = Ui.horizontal(a, Gravity.BOTTOM)
    private val homeClock = Ui.tabular(Ui.text(a, "", 24f, Ui.TEXT, Ui.SEMIBOLD))
    private val homeDate = Ui.text(a, "", 16f, Ui.TEXT2)
    private val titleView = Ui.ellipsize(Ui.text(a, "", 23f, Ui.TEXT, Ui.SEMIBOLD))
    private val lid = Ui.chip(a, tr("Couvercle ouvert"), Icon.Kind.LID, Ui.WARN, Ui.WARN_DIM)
    private val temp = Ui.chip(a, "", Icon.Kind.THERMO, Ui.TEXT2)
    private val state = Ui.horizontal(a)
    private val stateDot = View(a)
    private val stateText = Ui.text(a, "", 15f, Ui.TEXT2, Ui.MEDIUM)
    private val clock = Ui.tabular(Ui.text(a, "", 17f, Ui.TEXT, Ui.SEMIBOLD))
    private val themeIcon = Icon(a, Icon.Kind.MOON, Ui.TEXT2)
    private val themeBtn = FrameLayout(a)
    private val bell = FrameLayout(a)
    private val badge = Ui.text(a, "", 11f, Ui.ON_ACCENT, Ui.BOLD)
    private val gear = Ui.iconButton(a, Icon.Kind.SETTINGS, tr("Réglages"), Ui.Style.GHOST, size = 48, iconSize = 24) { a.openSettings() }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshClock()
            // Programmé avant : si le thème change, cette barre est remplacée et son minuteur arrêté.
            handler.postDelayed(this, 10_000)
            a.checkTheme()
        }
    }
    private var adminTaps = 0
    private var adminFirst = 0L
    private var mode = Mode.HOME

    init {
        orientation = HORIZONTAL
        setGravity(Gravity.CENTER_VERTICAL)
        setPadding(Ui.u(12), 0, Ui.u(12), 0)
        // Pas de fond propre : la barre se pose sur le fond de l'écran.
        back.background = Ui.pressable(Ui.round(0, 24f), 24f)
        addView(back, LayoutParams(Ui.u(48), Ui.u(48)).apply { rightMargin = Ui.u(2) })

        homeBox.addView(homeClock)
        homeBox.addView(homeDate, Ui.lp(Ui.WRAP, Ui.WRAP, l = 12, b = 2))
        addView(homeBox, LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.u(16) })
        addView(titleView, LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.u(6) })

        addView(lid, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        addView(temp, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        state.setPadding(Ui.u(12), Ui.u(7), Ui.u(14), Ui.u(7))
        state.background = Ui.round(Ui.SURFACE3, 40f)
        stateDot.background = Ui.round(Ui.OK, 4f)
        state.addView(stateDot, LayoutParams(Ui.u(8), Ui.u(8)).apply { rightMargin = Ui.u(8) })
        state.addView(stateText)
        addView(state, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        addView(clock, Ui.lp(Ui.WRAP, Ui.WRAP, l = 4, r = 6))

        themeBtn.background = Ui.pressable(Ui.round(0, 24f), 24f)
        themeBtn.addView(themeIcon, Ui.flp(Ui.u(22), Ui.u(22), Gravity.CENTER))
        themeBtn.isClickable = true
        themeBtn.setOnClickListener { a.toggleTheme() }
        addView(themeBtn, LayoutParams(Ui.u(48), Ui.u(48)))

        bell.background = Ui.pressable(Ui.round(0, 24f), 24f)
        bell.addView(Icon(a, Icon.Kind.BELL, Ui.TEXT), Ui.flp(Ui.u(24), Ui.u(24), Gravity.CENTER))
        badge.gravity = Gravity.CENTER
        badge.setPadding(Ui.u(5), 0, Ui.u(5), 0)
        badge.minWidth = Ui.u(20)
        badge.background = Ui.round(Ui.ACCENT, 10f, Ui.BG, 2f)
        bell.addView(badge, Ui.flp(Ui.WRAP, Ui.u(20), Gravity.TOP or Gravity.END).apply { topMargin = Ui.u(5); rightMargin = Ui.u(4) })
        bell.isClickable = true
        bell.setOnClickListener { a.openNotices() }
        addView(bell, LayoutParams(Ui.u(48), Ui.u(48)))
        addView(gear, LayoutParams(Ui.u(48), Ui.u(48)))

        lid.visibility = View.GONE
        temp.visibility = View.GONE
        badge.visibility = View.GONE
        // Accès caché aux réglages Android : 5 appuis rapides sur l'heure (pas une étape du bouton rotatif).
        for (v in listOf(homeBox, clock)) {
            Knob.skip(v)
            v.setOnClickListener { adminTap() }
        }
        refreshTheme()
        refreshClock()
    }

    private fun adminTap() {
        val now = System.currentTimeMillis()
        if (now - adminFirst > 3_000) { adminFirst = now; adminTaps = 0 }
        if (++adminTaps >= 5) {
            adminTaps = 0
            a.openAndroidSettings()
        }
    }

    fun startClock() {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 10_000)
        refreshClock()
    }

    fun stopClock() = handler.removeCallbacks(tick)

    private fun refreshClock() {
        val now = MainActivity.now()
        homeClock.text = now
        clock.text = now
        homeDate.text = SimpleDateFormat(tr("EEEE d MMMM"), I18n.locale).format(Date()).replaceFirstChar { it.uppercaseChar() }
    }

    private fun refreshTheme() {
        themeIcon.set(if (Ui.isDark) Icon.Kind.SUN else Icon.Kind.MOON)
        themeBtn.contentDescription = if (Ui.isDark) tr("Passer en thème clair") else tr("Passer en thème sombre")
    }

    fun setScreen(title: String, mode: Mode, fullScreen: Boolean, settings: Boolean) {
        this.mode = mode
        titleView.text = title
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.uf(if (fullScreen) 20f else 23f))
        back.visibility = if (mode == Mode.DETAIL) View.VISIBLE else View.GONE
        homeBox.visibility = if (mode == Mode.HOME) View.VISIBLE else View.GONE
        titleView.visibility = if (mode == Mode.HOME) View.GONE else View.VISIBLE
        (titleView.layoutParams as LayoutParams).leftMargin = Ui.u(if (mode == Mode.DETAIL) 4 else 16)
        clock.visibility = if (mode == Mode.HOME) View.GONE else View.VISIBLE
        // En plein écran (recette guidée) : rien qui détourne de la recette.
        bell.visibility = if (fullScreen) View.GONE else View.VISIBLE
        gear.visibility = if (fullScreen || settings) View.GONE else View.VISIBLE
        refreshTheme()
    }

    fun setBadge(count: Int) {
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
        badge.text = if (count > 9) "9+" else count.toString()
        bell.contentDescription = if (count > 0) tr("À savoir : %d nouveaux", count) else tr("À savoir")
    }

    fun update(s: Snapshot) {
        lid.visibility = if (s.hasSample && s.lidOpen) View.VISIBLE else View.GONE
        if (s.hasSample) {
            temp.visibility = View.VISIBLE
            Ui.chipText(temp).text = "${s.actualTempC} °C"
            val hot = s.actualTempC >= 40
            val col = if (hot) Ui.tempColor(s.actualTempC) else Ui.TEXT2
            Ui.chipText(temp).setTextColor(col)
            (temp.getChildAt(0) as Icon).color = col
            temp.background = Ui.round(if (hot) Ui.ACCENT_DIM else Ui.SURFACE3, 40f)
        } else temp.visibility = View.GONE
        val demo = a.prefs.mode == Prefs.Mode.DEMO
        val (label, color) = when {
            s.hasFault -> tr("Alerte") to Ui.DANGER
            s.busy && s.state == JobState.PAUSED -> tr("En pause") to Ui.WARN
            s.busy -> tr("En cuisson") to Ui.ACCENT
            s.link == LinkState.READY -> (if (demo) tr("Démo") else tr("Prête")) to (if (demo) Ui.COOL else Ui.OK)
            s.link == LinkState.ARMING -> tr("Connexion…") to Ui.WARN
            s.link == LinkState.LOST -> tr("Liaison perdue") to Ui.DANGER
            else -> tr("Base absente") to Ui.TEXT3
        }
        stateText.text = label
        stateDot.background = Ui.round(color, 4f)
        stateText.setTextColor(if (s.hasFault || s.link == LinkState.LOST) Ui.DANGER else Ui.TEXT2)
    }
}

/** Barre du bas : les cinq sections, toujours là (sauf en plein écran). */
class NavBar(private val a: MainActivity) : FrameLayout(a) {
    private class Item(val section: Section, val box: LinearLayout, val icon: Icon, val label: TextView)

    private val items = ArrayList<Item>()

    init {
        val bar = Ui.horizontal(a)
        bar.setPadding(Ui.u(6), Ui.u(6), Ui.u(6), Ui.u(6))
        bar.background = Ui.cardBg(31f)
        Ui.lift(bar, 4f)
        for ((i, s) in Section.values().withIndex()) {
            val box = Ui.horizontal(a, Gravity.CENTER)
            val icon = Icon(a, s.icon, Ui.TEXT2)
            box.addView(icon, LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)).apply { rightMargin = Ui.u(10) })
            val label = Ui.fitWords(Ui.text(a, s.label, 16f, Ui.TEXT2, Ui.MEDIUM), 13f)
            box.addView(label)
            box.isClickable = true
            box.contentDescription = s.label
            box.setOnClickListener { a.openSection(s) }
            bar.addView(box, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (i > 0) leftMargin = Ui.u(4) })
            items += Item(s, box, icon, label)
        }
        addView(bar, Ui.flp(Ui.MATCH, Ui.u(62)).apply { setMargins(Ui.u(24), Ui.u(10), Ui.u(24), Ui.u(14)) })
        setActive(Section.HOME)
    }

    fun setActive(section: Section?) {
        for (it in items) {
            val on = it.section == section
            it.box.background = Ui.pressable(if (on) Ui.round(Ui.ACCENT, 25f) else Ui.round(0, 25f), 25f)
            it.icon.color = if (on) Ui.ON_ACCENT else Ui.TEXT2
            it.label.setTextColor(if (on) Ui.ON_ACCENT else Ui.TEXT2)
            it.label.typeface = if (on) Ui.SEMIBOLD else Ui.MEDIUM
        }
    }
}

/** Fine barre de progression arrondie. */
class ProgressLine(c: Context) : View(c) {
    var progress = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var color = Ui.ACCENT
        set(v) {
            field = v
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        paint.color = Ui.SURFACE3
        r.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(r, h / 2, h / 2, paint)
        paint.color = color
        r.set(0f, 0f, maxOf(h, width * progress), h)
        if (progress > 0f) canvas.drawRoundRect(r, h / 2, h / 2, paint)
    }
}

/** Les étapes d'une recette en segments : faites (accent), en cours (accent pâle), à venir. */
class StepSegments(c: Context) : View(c) {
    private var count = 0
    private var index = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    fun set(count: Int, index: Int) {
        this.count = count
        this.index = index
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (count <= 0) return
        val gap = Ui.uf(if (count > 30) 2f else 4f)
        val w = (width - gap * (count - 1)) / count
        val h = height.toFloat()
        for (i in 0 until count) {
            paint.color = when {
                i < index -> Ui.ACCENT
                i == index -> (Ui.ACCENT and 0x00FFFFFF) or 0x73000000
                else -> Ui.SURFACE3
            }
            val x = i * (w + gap)
            r.set(x, 0f, x + w, h)
            canvas.drawRoundRect(r, h / 2, h / 2, paint)
        }
    }
}

/** Grand minuteur rond : un arc qui avance, la valeur au centre et une ligne sous elle. */
class TimerRing(c: Context) : FrameLayout(c) {
    private val arc = object : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val r = RectF()
        override fun onDraw(canvas: Canvas) {
            val sw = Ui.uf(10f)
            val size = minOf(width, height).toFloat()
            r.set((width - size) / 2 + sw / 2, (height - size) / 2 + sw / 2, (width + size) / 2 - sw / 2, (height + size) / 2 - sw / 2)
            paint.strokeWidth = sw
            paint.color = Ui.SURFACE3
            canvas.drawArc(r, 0f, 360f, false, paint)
            if (progress > 0f) {
                paint.color = arcColor
                canvas.drawArc(r, -90f, 360f * progress, false, paint)
            }
        }
    }
    private var progress = 0f
    private var arcColor = Ui.ACCENT
    val value: TextView = Ui.tabular(Ui.text(c, "", 46f, Ui.TEXT, Ui.LIGHT)).apply { gravity = Gravity.CENTER }
    val sub: TextView = Ui.text(c, "", 14f, Ui.TEXT2).apply { gravity = Gravity.CENTER }

    init {
        addView(arc, Ui.flp())
        val col = Ui.vertical(c, Gravity.CENTER)
        col.addView(value, Ui.lp(Ui.WRAP, Ui.WRAP))
        col.addView(sub, Ui.lp(Ui.WRAP, Ui.WRAP, t = 4))
        addView(col, Ui.flp(Ui.MATCH, Ui.MATCH, Gravity.CENTER))
    }

    fun set(progress: Float, value: String, sub: String, color: Int = Ui.ACCENT) {
        this.progress = progress.coerceIn(0f, 1f)
        arcColor = color
        this.value.text = value
        this.sub.text = sub
        arc.invalidate()
    }
}

/**
 * Barre « programme en cours », au-dessus de la barre du bas, sur tous les écrans (l'accueil
 * montre le programme en grand à la place) : ce qui tourne, le temps, Pause et Arrêter, et
 * « Voir ». Une alerte de sécurité prend sa place, en rouge, partout, même sur l'accueil.
 */
class LiveBar(private val a: MainActivity) : FrameLayout(a) {
    private val jobRow = Ui.horizontal(a)
    private val faultRow = Ui.horizontal(a)
    private val kindIcon = Icon(a, Icon.Kind.POT, Ui.ON_ACCENT)
    private val label = Ui.ellipsize(Ui.text(a, "", 17f, Ui.TEXT, Ui.SEMIBOLD))
    private val sub = Ui.ellipsize(Ui.text(a, "", 13f, Ui.TEXT2))
    private val time = Ui.tabular(Ui.text(a, "", 28f, Ui.TEXT, Ui.LIGHT))
    private val progress = ProgressLine(a)
    private val pauseBtn: FrameLayout
    private val stopBtn: FrameLayout
    private val okBtn: LinearLayout
    private val viewBtn: LinearLayout
    private val faultText = Ui.ellipsize(Ui.text(a, "", 18f, android.graphics.Color.WHITE, Ui.SEMIBOLD), 2)
    /** L'accueil montre le programme lui-même : la barre se retire (sauf alerte). */
    var hiddenForHome = false
        set(v) {
            field = v
            last?.let { update(it) }
        }
    private var last: Snapshot? = null

    init {
        jobRow.setPadding(Ui.u(9), Ui.u(8), Ui.u(8), Ui.u(8))
        jobRow.background = Ui.cardBg(32f)
        Ui.lift(jobRow, 6f)
        val circle = FrameLayout(a)
        circle.background = Ui.round(Ui.ACCENT, 23f)
        circle.addView(kindIcon, Ui.flp(Ui.u(22), Ui.u(22), Gravity.CENTER))
        jobRow.addView(circle, LinearLayout.LayoutParams(Ui.u(46), Ui.u(46)).apply { rightMargin = Ui.u(14) })
        val col = Ui.vertical(a)
        col.addView(label)
        col.addView(sub, Ui.lp(t = 3))
        jobRow.addView(col, LinearLayout.LayoutParams(Ui.u(240), Ui.WRAP))
        jobRow.addView(time, Ui.lp(Ui.WRAP, Ui.WRAP, l = 6, r = 16))
        jobRow.addView(progress, LinearLayout.LayoutParams(0, Ui.u(6), 1f).apply { rightMargin = Ui.u(16) })
        pauseBtn = Ui.iconButton(a, Icon.Kind.PAUSE, tr("Pause"), Ui.Style.SECONDARY, size = 48, iconSize = 20) { a.togglePause() }
        stopBtn = Ui.iconButton(a, Icon.Kind.STOP, tr("Arrêter"), Ui.Style.DANGER_SOFT, size = 48, iconSize = 18) { a.machine.stop() }
        okBtn = Ui.button(a, "OK", Ui.Style.ACCENT_SOFT, Icon.Kind.CHECK, height = 48, textSize = 16f) { a.machine.clearFinished() }
        viewBtn = Ui.button(a, tr("Voir"), Ui.Style.SECONDARY, height = 48, textSize = 15f) { a.openLive() }
        viewBtn.addView(Icon(a, Icon.Kind.CHEVRON, Ui.TEXT), LinearLayout.LayoutParams(Ui.u(18), Ui.u(18)).apply { leftMargin = Ui.u(4) })
        jobRow.addView(pauseBtn, LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)).apply { rightMargin = Ui.u(8) })
        jobRow.addView(stopBtn, LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)).apply { rightMargin = Ui.u(8) })
        jobRow.addView(okBtn, Ui.lp(Ui.WRAP, Ui.u(48), r = 8))
        jobRow.addView(viewBtn, Ui.lp(Ui.WRAP, Ui.u(48)))
        addView(jobRow, Ui.flp(Ui.MATCH, Ui.WRAP).apply { setMargins(Ui.u(24), Ui.u(8), Ui.u(24), 0) })

        faultRow.setPadding(Ui.u(18), Ui.u(10), Ui.u(10), Ui.u(10))
        faultRow.background = Ui.round(Ui.DANGER, 32f)
        faultRow.addView(Icon(a, Icon.Kind.WARNING, android.graphics.Color.WHITE), LinearLayout.LayoutParams(Ui.u(30), Ui.u(30)).apply { rightMargin = Ui.u(14) })
        faultRow.addView(faultText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val ack = Ui.button(a, tr("Acquitter"), Ui.Style.SECONDARY, Icon.Kind.CHECK, height = 52, textSize = 16f) {
            a.machine.acknowledge { v -> a.refusal(v) }
        }
        ack.background = Ui.pressable(Ui.round(android.graphics.Color.WHITE, 26f), 26f)
        (ack.getChildAt(0) as Icon).color = 0xFF241B14.toInt()
        (ack.getChildAt(1) as TextView).setTextColor(0xFF241B14.toInt())
        faultRow.addView(ack)
        addView(faultRow, Ui.flp(Ui.MATCH, Ui.WRAP).apply { setMargins(Ui.u(24), Ui.u(8), Ui.u(24), 0) })
        visibility = View.GONE
    }

    fun update(s: Snapshot) {
        last = s
        val showJob = (s.jobActive || s.turboActive) && !hiddenForHome
        visibility = if (showJob || s.hasFault) View.VISIBLE else View.GONE
        faultRow.visibility = if (s.hasFault) View.VISIBLE else View.GONE
        jobRow.visibility = if (!s.hasFault && showJob) View.VISIBLE else View.GONE
        if (s.hasFault) {
            faultText.text = s.faultMessage
            return
        }
        if (!showJob) return
        kindIcon.set(Live.kindIcon(if (s.turboActive) JobKind.TURBO else s.kind))
        if (s.turboActive) {
            label.text = "Turbo"
            sub.text = Live.stateText(s)
            sub.setTextColor(Ui.TEXT2)
            time.text = ""
            progress.progress = 0f
            setButtons(running = false, ended = false, turbo = true)
            return
        }
        label.text = s.jobLabel
        val st = s.state
        val settings = Live.settingsText(s)
        sub.text = if (st == JobState.RUNNING && settings.isNotEmpty()) settings else Live.stateText(s)
        sub.setTextColor(if (st == JobState.RUNNING) Ui.TEXT2 else Live.stateColor(s))
        time.text = Live.bigValue(s)
        progress.progress = Live.progress(s)
        progress.color = if (s.jobTempLevel > 0) Ui.ACCENT else Ui.COOL
        val ended = st == JobState.FINISHED || st == JobState.ABORTED
        setButtons(running = st != JobState.PAUSED, ended = ended, turbo = false)
        viewBtn.visibility = if (a.liveShownHere()) View.GONE else View.VISIBLE
    }

    private fun setButtons(running: Boolean, ended: Boolean, turbo: Boolean) {
        pauseBtn.visibility = if (ended || turbo) View.GONE else View.VISIBLE
        stopBtn.visibility = if (ended) View.GONE else View.VISIBLE
        okBtn.visibility = if (ended) View.VISIBLE else View.GONE
        viewBtn.visibility = if (turbo) View.GONE else View.VISIBLE
        (pauseBtn.getChildAt(0) as Icon).set(if (running) Icon.Kind.PAUSE else Icon.Kind.PLAY)
        pauseBtn.contentDescription = if (running) tr("Pause") else tr("Reprendre")
    }
}
