package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey
import app.toque.core.machine.Accessory
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobState
import app.toque.core.machine.Limits
import app.toque.core.machine.Programs
import app.toque.core.machine.SafetyPolicy
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.ui.HoldButton
import app.toque.ui.Icon
import app.toque.ui.Knob
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Section
import app.toque.ui.SpeedBars
import app.toque.ui.TempGauge
import app.toque.ui.Ui
import app.toque.ui.ValueCard
import app.toque.ui.shown

/**
 * Cuisson manuelle. Les réglages incompatibles sont corrigés à la saisie (vitesse limitée en
 * chauffe, en sens inverse ou avec le fouet) plutôt que refusés au démarrage.
 */
class ManualScreen(a: MainActivity, preset: app.toque.core.recipes.StepMachine? = null) : Screen(a) {
    /** Ouverte avec les réglages d'une étape (« Ajuster à la main ») : ce sont eux qui comptent. */
    private val fromPreset = preset != null
    override val title = JobKind.MANUAL.label
    // Ouverte depuis une étape de recette (« Ajuster à la main ») : écran de détail, pas la section.
    override val section: Section? = if (preset == null) Section.MANUAL else null

    private var seconds = preset?.seconds?.takeIf { it > 0 } ?: 60
    private var tempLevel = preset?.temperatureLevel ?: 0
    private var speed = preset?.speed?.coerceIn(1, 10) ?: 2
    private var reverse = preset?.reverse ?: false
    private var accessory = preset?.accessory ?: Accessory.BLADE
    private var selected = 0

    private lateinit var timeCard: ValueCard
    private lateinit var tempCard: ValueCard
    private lateinit var speedCard: ValueCard
    private lateinit var gauge: TempGauge
    private lateinit var bars: SpeedBars
    private lateinit var options: LinearLayout
    private lateinit var weight: TextView
    private lateinit var startBtn: LinearLayout
    private lateinit var liveRow: LinearLayout
    private lateinit var pauseBtn: LinearLayout
    private lateinit var hint: TextView
    private lateinit var turbo: HoldButton
    private var live = false
    private var wasLive = false
    private var pendingEdit = false
    private var timeEdited = false
    private var configuredSeconds = seconds
    private val pushLive = Runnable { sendAdjust() }

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(4), Ui.u(24), Ui.u(4))

        // ---- les trois réglages
        val cards = Ui.horizontal(c)
        timeCard = ValueCard(c, tr("Durée"), Icon.Kind.CLOCK)
        tempCard = ValueCard(c, tr("Température"), Icon.Kind.THERMO)
        speedCard = ValueCard(c, tr("Vitesse"), Icon.Kind.SPEED)
        val all = listOf(timeCard, tempCard, speedCard)
        for ((i, card) in all.withIndex()) {
            card.onSelect = { selected = i; refresh() }
            card.onMinus = { change(i, -1) }
            card.onPlus = { change(i, 1) }
            cards.addView(card, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (i < 2) rightMargin = Ui.u(16) })
        }
        // Durée : trois raccourcis.
        val presets = Ui.horizontal(c, Gravity.CENTER)
        for ((i, sec) in intArrayOf(30, 60, 300).withIndex()) {
            presets.addView(Knob.skip(Ui.toggleChip(c, Ui.duration(sec), false) { setSeconds(sec) }.apply {
                setPadding(Ui.u(12), Ui.u(7), Ui.u(12), Ui.u(7))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.uf(14f))
            }), Ui.lp(Ui.WRAP, Ui.WRAP, r = if (i < 2) 6 else 0))
        }
        timeCard.extra.addView(presets, Ui.flp(Ui.MATCH, Ui.WRAP))
        // Température : la jauge et ses repères.
        val tempBox = Ui.vertical(c)
        gauge = TempGauge(c)
        tempBox.addView(gauge, Ui.lp(Ui.MATCH, Ui.u(24)))
        val marks = Ui.horizontal(c)
        marks.addView(Ui.text(c, tr("sans"), 13f, Ui.TEXT2), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        marks.addView(Ui.text(c, "130 °C", 13f, Ui.TEXT2))
        tempBox.addView(marks, Ui.lp(t = 2))
        tempCard.extra.addView(tempBox, Ui.flp(Ui.MATCH, Ui.WRAP))
        // Vitesse : les dix barres.
        bars = SpeedBars(c)
        speedCard.extra.addView(bars, Ui.flp(Ui.MATCH, Ui.u(30)))
        root.addView(cards, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        // ---- en bas : sens et accessoire, balance, Démarrer et Turbo
        val bottom = Ui.horizontal(c, Gravity.TOP)
        options = Ui.vertical(c, Gravity.CENTER_VERTICAL)
        options.background = Ui.cardBg(24f)
        Ui.lift(options)
        options.setPadding(Ui.u(20), Ui.u(14), Ui.u(20), Ui.u(14))
        bottom.addView(options, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { rightMargin = Ui.u(16) })

        val scale = Ui.vertical(c)
        scale.background = Ui.cardBg(24f)
        Ui.lift(scale)
        scale.setPadding(Ui.u(20), Ui.u(14), Ui.u(16), Ui.u(14))
        val sh = Ui.horizontal(c)
        sh.addView(Icon(c, Icon.Kind.SCALE, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(18), Ui.u(18)).apply { rightMargin = Ui.u(8) })
        sh.addView(Ui.text(c, tr("Balance"), 15f, Ui.TEXT2, Ui.SEMIBOLD))
        scale.addView(sh)
        weight = Ui.tabular(Ui.text(c, "—", 32f, Ui.TEXT, Ui.LIGHT))
        scale.addView(weight, Ui.lp(t = 4))
        scale.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        scale.addView(Ui.button(c, tr("Tarer"), Ui.Style.SECONDARY, height = 40, textSize = 15f) { a.machine.tare() }, Ui.lp(Ui.WRAP, Ui.WRAP))
        bottom.addView(scale, LinearLayout.LayoutParams(Ui.u(190), Ui.MATCH).apply { rightMargin = Ui.u(16) })

        val actions = Ui.vertical(c, Gravity.CENTER_VERTICAL)
        hint = Ui.ellipsize(Ui.text(c, "", 13f, Ui.WARN, Ui.MEDIUM).apply { gravity = Gravity.CENTER }, 2)
        actions.addView(hint, Ui.lp(b = 6))
        startBtn = Ui.button(c, tr("Démarrer"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 62, textSize = 20f) { start() }
        actions.addView(startBtn, Ui.lp(Ui.MATCH, Ui.WRAP))
        turbo = HoldButton(c, tr("Turbo : maintenir"), onDown = {
            a.machine.turboDown(accessory) { v -> if (!v.ok) a.refusal(v) }
        }, onUp = { a.machine.turboUp() })
        actions.addView(turbo, Ui.lp(Ui.MATCH, Ui.u(48), t = 8))
        // Pendant sa propre cuisson, l'écran garde ses cartes entières (− et + au doigt) : Pause et
        // Arrêter viennent ici, à la place de Démarrer et Turbo, et la barre « en cours » s'efface.
        liveRow = Ui.horizontal(c)
        pauseBtn = Ui.button(c, tr("Pause"), Ui.Style.SECONDARY, Icon.Kind.PAUSE, height = 62, textSize = 18f) { a.togglePause() }
        liveRow.addView(pauseBtn, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(10) })
        liveRow.addView(Ui.button(c, tr("Arrêter"), Ui.Style.DANGER_SOFT, Icon.Kind.STOP, height = 62, textSize = 18f) { a.machine.stop() },
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        liveRow.visibility = View.GONE
        actions.addView(liveRow, Ui.lp(Ui.MATCH, Ui.WRAP))
        bottom.addView(actions, LinearLayout.LayoutParams(Ui.u(268), Ui.MATCH))
        root.addView(bottom, Ui.lp(Ui.MATCH, Ui.u(142), t = 16))
        refresh()
        return root
    }

    /** Raccourci de durée (30 s, 1 min, 5 min), dans les limites de la vitesse choisie. */
    private fun setSeconds(sec: Int) {
        selected = 0
        val max = ((if (speed > 6) Limits.MAX_MS_HIGH_SPEED else Limits.MAX_MS_DEFAULT) / 1000).toInt()
        seconds = sec.coerceIn(5, max)
        refresh()
        if (live && a.snapshot.kind?.name == "MANUAL") {
            timeEdited = true
            pendingEdit = true
            removeCallbacks(pushLive)
            postDelayed(pushLive, 350)
        }
    }

    private val tempSteps: IntArray get() = intArrayOf(0) + (1..19).toList().toIntArray()

    private fun nextSeconds(s: Int, dir: Int): Int {
        val step = when {
            dir > 0 && s < 60 || dir < 0 && s <= 60 -> 5
            dir > 0 && s < 600 || dir < 0 && s <= 600 -> 30
            else -> 60
        }
        val max = ((if (speed > 6) Limits.MAX_MS_HIGH_SPEED else Limits.MAX_MS_DEFAULT) / 1000).toInt()
        return (s + dir * step).coerceIn(5, max)
    }

    private fun change(card: Int, dir: Int) {
        val s = a.snapshot
        when (card) {
            0 -> seconds = nextSeconds(seconds, dir)
            1 -> {
                val i = tempSteps.indexOf(tempLevel).coerceAtLeast(0)
                tempLevel = tempSteps[(i + dir).coerceIn(0, tempSteps.size - 1)]
                if (tempLevel > 0 && speed > Limits.MAX_SPEED_HEATED) {
                    speed = Limits.MAX_SPEED_HEATED
                    a.toast(tr("En chauffe, vitesse %d maximum", Limits.MAX_SPEED_HEATED))
                }
            }
            2 -> {
                var max = 10
                var why = trKey("Vitesse %d maximum")
                if (tempLevel > 0) { max = Limits.MAX_SPEED_HEATED; why = trKey("En chauffe, vitesse %d maximum") }
                if (reverse && max > Limits.MAX_SPEED_REVERSE) { max = Limits.MAX_SPEED_REVERSE; why = trKey("En sens inverse, vitesse %d maximum") }
                if (accessory.maxSpeed < max) { max = accessory.maxSpeed; why = trKey("Avec le fouet, vitesse %d maximum") }
                val next = (speed + dir).coerceIn(1, 10)
                if (next > max) a.toast(tr(why, max)) else speed = next
            }
        }
        val maxS = ((if (speed > 6) Limits.MAX_MS_HIGH_SPEED else Limits.MAX_MS_DEFAULT) / 1000).toInt()
        if (seconds > maxS) seconds = maxS
        refresh()
        if (live && s.kind?.name == "MANUAL") {
            if (card == 0) timeEdited = true
            pendingEdit = true
            removeCallbacks(pushLive)
            postDelayed(pushLive, 350)
        }
    }

    private fun setReverse(r: Boolean) {
        if (r && speed > Limits.MAX_SPEED_REVERSE) {
            speed = Limits.MAX_SPEED_REVERSE
            a.toast(tr("En sens inverse, vitesse %d maximum", Limits.MAX_SPEED_REVERSE))
        }
        reverse = r
        refresh()
        if (live) { pendingEdit = true; removeCallbacks(pushLive); postDelayed(pushLive, 200) }
    }

    private fun setAccessory(acc: Accessory) {
        if (live) return
        accessory = acc
        if (speed > acc.maxSpeed) {
            speed = acc.maxSpeed
            a.toast(tr("%s : vitesse %d maximum", acc.label, acc.maxSpeed))
        }
        refresh()
    }

    private fun spec(hot: Boolean = false) = Programs.manual(
        speed, tempLevel, if (reverse) Direction.COUNTER_CLOCKWISE else Direction.CLOCKWISE,
        seconds * 1000L, accessory, hot,
    )

    private fun start() {
        val s = a.snapshot
        if (s.state == JobState.PAUSED && s.kind?.name == "MANUAL") {
            a.machine.resume(s.hotBlendConfirmed) { a.refusal(it) }
            return
        }
        configuredSeconds = seconds
        a.startJob(spec())
    }

    private fun sendAdjust() {
        pendingEdit = false
        val s = a.snapshot
        if (!s.jobActive || s.kind?.name != "MANUAL") return
        // La durée n'est renvoyée que si elle a été modifiée : changer la vitesse ne relance pas le minuteur.
        val total: Long? = if (timeEdited) s.elapsedMs + seconds * 1000L else null
        timeEdited = false
        a.machine.adjust(speed, tempLevel, reverse, total, s.hotBlendConfirmed) { v ->
            if (!v.ok && v.has(SafetyPolicy.HOT_BLEND)) {
                a.confirm(tr("Contenu chaud"), tr("Le bol est à %d °C. Passer à la vitesse %d ?", a.snapshot.actualTempC, speed), tr("Confirmer"), icon = Icon.Kind.THERMO) {
                    a.machine.adjust(speed, tempLevel, reverse, total, true) { a.refusal(it) }
                }
            } else a.refusal(v)
        }
    }

    /** Vitesse la plus haute permise avec les réglages choisis (chauffe, sens inverse, fouet). */
    private fun maxSpeed(): Int {
        var max = 10
        if (tempLevel > 0) max = minOf(max, Limits.MAX_SPEED_HEATED)
        if (reverse) max = minOf(max, Limits.MAX_SPEED_REVERSE)
        return minOf(max, accessory.maxSpeed)
    }

    /** La carte Température : la consigne, et la température mesurée du bol dessous. */
    private fun refreshTempCard() {
        val measured = if (a.snapshot.hasSample) tr("bol à %d °C", a.snapshot.actualTempC) else ""
        if (tempLevel == 0) tempCard.set("—", "", listOf(tr("Sans chauffe"), measured).filter { it.isNotEmpty() }.joinToString(" · "), Ui.TEXT2)
        else {
            val t = Mcc.celsiusForLevel(tempLevel)
            tempCard.set("$t", "°C", if (measured.isNotEmpty()) measured else tr("vitesse %d au plus en chauffe", Limits.MAX_SPEED_HEATED), Ui.tempColor(t))
        }
    }

    private fun refresh() {
        val maxS = if (speed > 6) tr("15 min au plus") else tr("99 min au plus")
        timeCard.set(Ui.clock(seconds * 1000L), "", maxS)
        refreshTempCard()
        gauge.position = tempSteps.indexOf(tempLevel).coerceAtLeast(0) / (tempSteps.size - 1f)
        speedCard.set("$speed", "", when (speed) {
            1 -> tr("Mijoter")
            2, 3 -> tr("Mélanger")
            in 4..6 -> tr("Hacher")
            else -> tr("Mixer")
        } + if (reverse) " · " + tr("inverse") else "")
        bars.set(speed, maxSpeed())
        timeCard.select(selected == 0)
        tempCard.select(selected == 1)
        speedCard.select(selected == 2)

        options.removeAllViews()
        fun line(label: String, choices: List<String>, sel: Int, onPick: (Int) -> Unit, top: Int) {
            val row = Ui.horizontal(c)
            row.addView(Ui.text(c, label, 15f, Ui.TEXT2, Ui.SEMIBOLD), LinearLayout.LayoutParams(Ui.u(112), Ui.WRAP))
            row.addView(Ui.segmented(c, choices, sel, 48, onPick), LinearLayout.LayoutParams(0, Ui.u(48), 1f))
            options.addView(row, Ui.lp(t = top))
        }
        line(tr("Sens"), listOf(tr("Normal"), tr("Inverse")), if (reverse) 1 else 0, { setReverse(it == 1) }, 0)
        line(tr("Accessoire"), listOf(Accessory.BLADE.label, Accessory.WHISK.label), if (accessory == Accessory.WHISK) 1 else 0,
            { setAccessory(if (it == 1) Accessory.WHISK else Accessory.BLADE) }, 12)

        val v = SafetyPolicy.checkJob(spec(), if (a.snapshot.hasSample) a.snapshot.actualTempC else null)
            .filter { it.code != SafetyPolicy.HOT_BLEND }
        hint.shown = v.firstOrNull()?.message ?: if (a.snapshot.hot && speed >= 4) tr("Contenu chaud : confirmation demandée") else ""
        hint.visibility = if (hint.text.isEmpty()) View.GONE else View.VISIBLE
        Ui.setButtonEnabled(startBtn, v.isEmpty())
    }

    override fun onSnapshot(s: Snapshot) {
        weight.shown = if (s.hasSample) "${s.weightG} g" else "—"
        val manualJob = s.kind?.name == "MANUAL" && (s.state == JobState.RUNNING || s.state == JobState.STARTING || s.state == JobState.PAUSED)
        // Écran ouvert sans réglages sur une cuisson manuelle déjà lancée (« Voir », la section) : il
        // reprend ceux de la cuisson, à chaque phase, pour qu'un ajustement ne renvoie pas ceux par
        // défaut. Ouvert depuis une étape (« Ajuster à la main »), il garde les réglages de l'étape.
        if (!fromPreset && manualJob && (syncedPhase != s.phaseIndex || !syncedFromJob) && !pendingEdit) {
            syncedFromJob = true
            syncedPhase = s.phaseIndex
            tempLevel = s.jobTempLevel
            if (s.jobSpeed > 0) speed = s.jobSpeed.coerceIn(1, 10)
            reverse = s.jobReverse
            accessory = try { Accessory.valueOf(s.accessory) } catch (_: Exception) { accessory }
            refresh()
        }
        if (!manualJob) { syncedFromJob = false; syncedPhase = -1 }
        live = manualJob && s.state != JobState.PAUSED
        bars.setActual(if (live) s.speedLevel else -1)
        tempCard.repeatEnabled = !live
        speedCard.repeatEnabled = !live
        if (live && !pendingEdit) {
            // Pendant la cuisson, la carte Durée affiche le temps restant.
            val remaining = ((s.remainingMs + 999) / 1000).toInt().coerceAtLeast(1)
            if (remaining != seconds) {
                seconds = remaining
                timeCard.set(Ui.clock(seconds * 1000L), "", tr("restant"))
            }
        }
        if (wasLive && !live && !manualJob) {
            seconds = configuredSeconds
            refresh()
        }
        wasLive = live
        val busy = s.busy
        val own = showsLive(s) && !s.turboActive
        liveRow.visibility = if (own) View.VISIBLE else View.GONE
        startBtn.visibility = if (own || (busy && s.state != JobState.PAUSED)) View.GONE else View.VISIBLE
        (startBtn.getChildAt(1) as TextView).shown = tr("Démarrer")
        turbo.visibility = if (own) View.GONE else View.VISIBLE
        turbo.isEnabled = !busy || s.turboActive
        turbo.alpha = if (turbo.isEnabled) 1f else 0.35f
        if (own) {
            val paused = s.state == JobState.PAUSED
            (pauseBtn.getChildAt(0) as Icon).set(if (paused) Icon.Kind.PLAY else Icon.Kind.PAUSE)
            (pauseBtn.getChildAt(1) as TextView).shown = if (paused) tr("Reprendre") else tr("Pause")
        }
        if (live) hint.shown = tr("Réglages appliqués en direct")
        else if (!busy) refreshHintOnly()
        hint.setTextColor(if (live) Ui.OK else Ui.WARN)
        hint.visibility = if (hint.text.isEmpty()) View.GONE else View.VISIBLE
        // La température du bol, sous la consigne.
        if (s.hasSample != lastSample || (s.hasSample && s.actualTempC != lastTemp)) {
            lastSample = s.hasSample
            lastTemp = s.actualTempC
            refreshTempCard()
        }
    }

    /**
     * Sa propre cuisson (manuelle, en marche ou en pause) ou un Turbo : l'écran la montre en entier
     * et la barre « en cours » s'efface (elle revient pour le OK de fin).
     */
    override fun showsLive(s: Snapshot): Boolean = s.turboActive ||
        (s.kind?.name == "MANUAL" && (s.state == JobState.RUNNING || s.state == JobState.STARTING ||
            s.state == JobState.PAUSED || s.state == JobState.STOPPING))

    private var lastSample = false
    private var lastTemp = -1
    private var syncedFromJob = false
    private var syncedPhase = -1

    private fun refreshHintOnly() {
        val v = SafetyPolicy.checkJob(spec(), if (a.snapshot.hasSample) a.snapshot.actualTempC else null)
            .filter { it.code != SafetyPolicy.HOT_BLEND }
        hint.shown = v.firstOrNull()?.message ?: if (a.snapshot.hot && speed >= 4) tr("Contenu chaud : confirmation demandée") else ""
    }

    /** Au bouton rotatif : on arrive sur la durée ; Démarrer se touche comme du doigt (jamais d'un seul appui). */
    override val knobStart: View? get() = if (::timeCard.isInitialized) timeCard else null
}
