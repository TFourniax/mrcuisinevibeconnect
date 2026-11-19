package app.toque.ui.screens

import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.toque.Prefs
import app.toque.core.i18n.tr
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Mcc
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/** Valeurs brutes de la base moteur, trames émises/reçues, et commandes de la base simulée. */
class DiagnosticsScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Diagnostic")
    private lateinit var table: TextView
    private lateinit var frames: TextView

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(20))
        val left = Ui.card(c, 22f)
        table = Ui.text(c, "", 16f, Ui.TEXT).apply { typeface = Typeface.MONOSPACE; setLineSpacing(0f, 1.2f) }
        left.addView(Ui.scroll(c, table), Ui.lp(Ui.MATCH, Ui.MATCH))
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { rightMargin = Ui.u(16) })
        val right = Ui.vertical(c)
        val fcard = Ui.card(c, 22f)
        fcard.addView(Ui.text(c, tr("TRAMES"), 15f, Ui.TEXT2, Ui.MEDIUM))
        frames = Ui.text(c, "", 15f, Ui.COOL).apply { typeface = Typeface.MONOSPACE; setLineSpacing(0f, 1.25f) }
        fcard.addView(frames, Ui.lp(t = 8))
        right.addView(fcard)
        if (a.prefs.mode == Prefs.Mode.DEMO) {
            val sim = Ui.card(c, 22f)
            sim.addView(Ui.text(c, tr("BASE SIMULÉE"), 15f, Ui.TEXT2, Ui.MEDIUM))
            val r1 = Ui.horizontal(c)
            r1.addView(Ui.button(c, tr("Ouvrir couvercle"), Ui.Style.SECONDARY, Icon.Kind.LID, height = 54, textSize = 16f) { a.machine.simulate(lid = true) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
            r1.addView(Ui.button(c, tr("Fermer"), Ui.Style.SECONDARY, height = 54, textSize = 16f) { a.machine.simulate(lid = false) })
            sim.addView(r1, Ui.lp(t = 10))
            val r2 = Ui.horizontal(c)
            r2.addView(Ui.button(c, tr("Erreur moteur"), Ui.Style.SECONDARY, Icon.Kind.WARNING, height = 54, textSize = 16f) { a.machine.simulate(error = 5) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
            r2.addView(Ui.button(c, tr("Effacer"), Ui.Style.SECONDARY, height = 54, textSize = 16f) { a.machine.simulate(error = 0) })
            sim.addView(r2, Ui.lp(t = 10))
            right.addView(sim, Ui.lp(t = 14))
        }
        root.addView(right, LinearLayout.LayoutParams(Ui.u(420), Ui.MATCH))
        return root
    }

    override fun onSnapshot(s: Snapshot) {
        fun yn(b: Boolean) = if (b) tr("oui") else tr("non")
        table.text = buildString {
            append(tr("Liaison").padEnd(17)).append(s.link.label).append('\n')
            append(tr("Transport").padEnd(17)).append(s.transportName.ifEmpty { "—" }).append(if (s.transportUp) " (" + tr("ouvert") + ")" else " (" + tr("fermé") + ")").append('\n')
            append(tr("Âge échantillon").padEnd(17)).append(if (s.sampleAgeMs < 0) "—" else "${s.sampleAgeMs} ms").append('\n')
            append(tr("Alerte").padEnd(17)).append(s.faultMessage.ifEmpty { tr("aucune") }).append('\n')
            append(tr("Moteur / chauffe").padEnd(17)).append(yn(s.motorOn)).append(" / ").append(yn(s.heaterOn)).append('\n')
            append(tr("Régime").padEnd(17)).append(s.rpm).append(" ").append(tr("tr/min (niveau %d)", s.speedLevel)).append("\n")
            append(tr("Température").padEnd(17)).append(s.actualTempC).append(" ").append(tr("°C (consigne %d °C)", s.targetTempC)).append("\n")
            append(tr("Poids").padEnd(17)).append(s.weightG).append(" g · ").append(s.scaleState).append(if (s.scaleStable) " · " + tr("stable") else "").append('\n')
            append(tr("Couvercle ouvert").padEnd(17)).append(yn(s.lidOpen)).append('\n')
            append(tr("Bol détecté").padEnd(17)).append(yn(s.bowlDetected)).append('\n')
            append(tr("Erreur base").padEnd(17)).append(if (s.boardErrorCode == 0) tr("aucune") else "${s.boardErrorCode} · ${Mcc.boardErrorMessage(s.boardErrorCode)}").append('\n')
            append(tr("Firmware base").padEnd(17)).append(s.firmware).append('\n')
            append(tr("Programme").padEnd(17)).append(s.jobLabel.ifEmpty { "—" }).append(' ').append(s.jobState).append('\n')
            append(tr("Commande").padEnd(17)).append(s.wireOperation).append(" v").append(s.wireSpeed).append(" t").append(s.wireTempLevel).append('\n')
            append(tr("Trames TX/RX").padEnd(17)).append(s.txFrames).append(" / ").append(s.rxFrames).append(" · ").append(tr("invalides %d", s.invalidFrames))
        }
        frames.text = "TX " + s.txHex.ifEmpty { "—" } + "\n\nRX " + s.rxHex.ifEmpty { "—" }
    }
}

/** Journal de l'appli (démarrages, synchros, alertes, plantages). */
class LogScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Journal")
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(20))
        val card = Ui.card(c, 22f)
        text = Ui.text(c, "", 14f, Ui.TEXT2).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        scroll = Ui.scroll(c, text)
        card.addView(scroll, Ui.lp(Ui.MATCH, Ui.MATCH))
        root.addView(card, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        val row = Ui.horizontal(c)
        row.addView(Ui.button(c, tr("Rafraîchir"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 56) { load() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        row.addView(Ui.button(c, tr("Effacer"), Ui.Style.GHOST, height = 56) { a.app.log.clear(); load() })
        root.addView(row, Ui.lp(t = 12))
        return root
    }

    private fun load() {
        text.text = a.app.log.read().ifEmpty { tr("Journal vide.") }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onShow() = load()
}

/** Réglage du bouton rotatif : on tourne, l'appli retient les codes reçus. */
class KnobScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Bouton rotatif")
    private lateinit var info: TextView
    private lateinit var last: TextView
    private var capture: String? = null

    override fun build(): View {
        val root = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
        root.setPadding(Ui.u(40), Ui.u(20), Ui.u(40), Ui.u(20))
        info = Ui.text(c, "", 20f, Ui.TEXT2).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }
        root.addView(info, Ui.lp(Ui.MATCH, Ui.WRAP))
        last = Ui.text(c, "—", 60f, Ui.TEXT, Ui.THIN)
        root.addView(last, Ui.lp(Ui.WRAP, Ui.WRAP, t = 20))
        val row = Ui.horizontal(c, Gravity.CENTER)
        row.addView(Ui.button(c, tr("Tourner à gauche"), Ui.Style.SECONDARY, height = 60) { ask("left") }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        row.addView(Ui.button(c, tr("Tourner à droite"), Ui.Style.SECONDARY, height = 60) { ask("right") }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        row.addView(Ui.button(c, tr("Appuyer"), Ui.Style.SECONDARY, height = 60) { ask("press") }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        row.addView(Ui.button(c, tr("Par défaut"), Ui.Style.GHOST, height = 60) {
            a.prefs.knobLeft = -1; a.prefs.knobRight = -1; a.prefs.knobPress = -1
            refresh()
        })
        root.addView(row, Ui.lp(t = 30))
        root.addView(Ui.text(c, tr("Partout dans Toque, tourner le bouton passe d'un bouton de l'écran à l'autre et appuyer le touche. " +
            "Sur une valeur (durée, température, vitesse…), un appui permet de la régler en tournant, un second appui la valide."),
            17f, Ui.TEXT3).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) }, Ui.lp(Ui.MATCH, Ui.WRAP, t = 28))
        refresh()
        return root
    }

    private fun ask(which: String) {
        capture = which
        info.text = tr("Fais maintenant l'action « %s » sur le bouton du robot…", when (which) {
            "left" -> tr("tourner à gauche")
            "right" -> tr("tourner à droite")
            else -> tr("appuyer")
        })
        a.knobListener = { e -> onKey(e) }
    }

    private fun onKey(e: KeyEvent) {
        last.text = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        when (capture) {
            "left" -> a.prefs.knobLeft = e.keyCode
            "right" -> a.prefs.knobRight = e.keyCode
            "press" -> a.prefs.knobPress = e.keyCode
        }
        capture = null
        a.knobListener = null
        refresh()
    }

    private fun refresh() {
        fun name(k: Int, def: String) = if (k < 0) tr("%s (par défaut)", def) else KeyEvent.keyCodeToString(k).removePrefix("KEYCODE_")
        info.text = tr("Gauche : %s\nDroite : %s\nAppui : %s\n\nLes molettes (défilement) sont aussi reconnues automatiquement.",
            name(a.prefs.knobLeft, "F7, DPAD_LEFT"), name(a.prefs.knobRight, "F6, DPAD_RIGHT"), name(a.prefs.knobPress, "F5, DPAD_CENTER"))
    }

    override fun onHide() {
        a.knobListener = null
    }

    override fun onKnob(delta: Int): Boolean {
        last.text = if (delta > 0) "→" else "←"
        return true
    }

    override fun onKnobPress(): Boolean {
        last.text = "●"
        return true
    }
}

/**
 * Recherche de l'anneau lumineux du bouton rotatif parmi les voyants de la tablette
 * (/sys/class/leds) : essayer d'allumer ou d'éteindre chacun, désigner celui qui commande
 * l'anneau, ou observer ce que fait l'appli d'origine pendant qu'elle tourne.
 */
class RingScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Anneau lumineux")
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var observed: LinearLayout
    private var loading = false

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(28), Ui.u(6), Ui.u(28), Ui.u(20))
        root.addView(Ui.text(c, tr("Pour éteindre l'anneau du bouton quand personne ne se sert du robot, Toque doit savoir comment la tablette le commande. " +
            "Essaie « Éteindre » sur chaque voyant : celui qui éteint l'anneau, désigne-le avec « C'est l'anneau »."), 17f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) })
        status = Ui.text(c, "", 17f, Ui.TEXT).apply { setLineSpacing(0f, 1.15f) }
        root.addView(status, Ui.lp(t = 10))
        val actions = Ui.horizontal(c)
        actions.addView(Ui.button(c, tr("Observer l'appli d'origine"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 54, textSize = 17f) { observe() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        actions.addView(Ui.button(c, tr("Actualiser"), Ui.Style.GHOST, height = 54, textSize = 17f) { load() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        if (a.prefs.ringLed.isNotEmpty()) {
            actions.addView(Ui.button(c, tr("Ne plus piloter l'anneau"), Ui.Style.GHOST, height = 54, textSize = 17f) {
                // L'anneau est rendu allumé, comme avant que Toque s'en occupe.
                val old = a.prefs.ringLed
                a.prefs.ringLed = ""
                a.ringChanged()
                set(old, true)
            })
        }
        root.addView(actions, Ui.lp(t = 12))
        observed = Ui.vertical(c)
        root.addView(observed, Ui.lp(t = 8))
        list = Ui.vertical(c)
        root.addView(list, Ui.lp(t = 8))
        return Ui.scroll(c, root)
    }

    override fun onShow() {
        load()
        renderObserved()
    }

    /** Retour de l'appli d'origine : le relevé est complet. */
    fun refreshObserved() {
        renderObserved()
        load()
    }

    private fun load() {
        if (loading) return
        loading = true
        status.text = tr("Lecture des voyants…")
        Thread {
            val leds = app.toque.ui.RingLight.list()
            main.post {
                loading = false
                render(leds)
            }
        }.start()
    }

    private fun render(leds: List<app.toque.core.ui.Led>) {
        list.removeAllViews()
        val chosen = a.prefs.ringLed
        status.text = when {
            leds.isEmpty() -> tr("La tablette ne montre aucun voyant pilotable : l'anneau est sans doute câblé en direct.")
            chosen.isNotEmpty() -> tr("Anneau : voyant « %s ». Réglages › Anneau lumineux choisit quand il s'allume.", chosen)
            else -> tr("%d voyants trouvés.", leds.size)
        }
        for (led in leds) {
            val card = Ui.card(c, 18f)
            val head = Ui.horizontal(c)
            val info = tr("luminosité %d / %d", led.brightness, led.max) + (if (led.trigger.isNotEmpty() && led.trigger != "none") " · " + tr("déclencheur %s", led.trigger) else "")
            val texts = Ui.vertical(c)
            texts.addView(Ui.text(c, led.name, 19f, if (led.name == chosen) Ui.ACCENT else Ui.TEXT, Ui.MEDIUM))
            texts.addView(Ui.text(c, info, 15f, Ui.TEXT3))
            head.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            if (app.toque.core.ui.Leds.isScreen(led.name)) {
                head.addView(Ui.text(c, tr("écran, non modifiable"), 15f, Ui.TEXT3))
            } else {
                head.addView(Ui.button(c, tr("Allumer"), Ui.Style.SECONDARY, height = 50, textSize = 16f) { set(led.name, true) }, Ui.lp(Ui.WRAP, Ui.WRAP, l = 8))
                head.addView(Ui.button(c, tr("Éteindre"), Ui.Style.SECONDARY, height = 50, textSize = 16f) { set(led.name, false) }, Ui.lp(Ui.WRAP, Ui.WRAP, l = 8))
                head.addView(Ui.button(c, tr("C'est l'anneau"), if (led.name == chosen) Ui.Style.PRIMARY else Ui.Style.GHOST, height = 50, textSize = 16f) { choose(led.name) },
                    Ui.lp(Ui.WRAP, Ui.WRAP, l = 8))
            }
            card.addView(head)
            list.addView(card, Ui.lp(t = 10))
        }
    }

    private fun set(name: String, lit: Boolean) {
        Thread {
            val ok = app.toque.ui.RingLight.set(name, lit)
            main.post {
                if (!ok) a.toast(tr("Le voyant n'a pas répondu (root ?)"), Ui.WARN)
                load()
            }
        }.start()
    }

    private fun choose(name: String) {
        if (!app.toque.core.ui.Leds.validName(name) || app.toque.core.ui.Leds.isScreen(name)) return
        a.prefs.ringLed = name
        a.app.log.write("anneau", "anneau désigné : $name")
        a.toast(tr("Anneau enregistré. Réglages › Anneau lumineux choisit quand il s'allume."), Ui.OK)
        a.ringChanged()
        load()
        renderObserved()
    }

    /** Démarre l'observation (3 minutes) puis ouvre l'appli d'origine ; le résultat s'affiche au retour. */
    private fun observe() {
        a.openVendorApp {
            if (!app.toque.ui.RingWatch.start(a.app.log)) a.toast(tr("Observation déjà en cours"), Ui.TEXT2)
        }
    }

    private fun renderObserved() {
        observed.removeAllViews()
        val moved = app.toque.ui.RingWatch.moved()
        if (app.toque.ui.RingWatch.running) {
            observed.addView(Ui.text(c, tr("Observation en cours (3 minutes au plus)…"), 16f, Ui.TEXT2))
        }
        if (moved.isEmpty()) {
            if (app.toque.ui.RingWatch.finished && !app.toque.ui.RingWatch.running) {
                observed.addView(Ui.text(c, tr("Aucun voyant n'a changé pendant l'observation : l'appli d'origine ne commande sans doute pas l'anneau par ici."), 16f, Ui.TEXT2)
                    .apply { setLineSpacing(0f, 1.15f) })
            }
            return
        }
        val card = Ui.card(c, 18f)
        card.addView(Ui.text(c, tr("Ce qui a changé pendant l'appli d'origine"), 18f, Ui.TEXT, Ui.MEDIUM))
        val changes = app.toque.ui.RingWatch.changes()
        for ((name, count) in moved) {
            val row = Ui.horizontal(c)
            val times = changes.filter { it.name == name }.take(6).joinToString(", ") { "${it.atMs / 1000} s : ${it.from} → ${it.to}" }
            val texts = Ui.vertical(c)
            texts.addView(Ui.text(c, tr("%s · %d changements", name, count), 17f, Ui.TEXT))
            texts.addView(Ui.text(c, times, 14f, Ui.TEXT3))
            row.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            if (!app.toque.core.ui.Leds.isScreen(name)) {
                row.addView(Ui.button(c, tr("C'est l'anneau"), Ui.Style.GHOST, height = 50, textSize = 16f) { choose(name) }, Ui.lp(Ui.WRAP, Ui.WRAP, l = 8))
            }
            card.addView(row, Ui.lp(t = 8))
        }
        observed.addView(card)
    }
}
