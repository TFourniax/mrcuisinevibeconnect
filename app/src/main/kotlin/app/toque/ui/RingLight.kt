package app.toque.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.toque.Logbook
import app.toque.Prefs
import app.toque.core.foyer.ScreensaverConfig
import app.toque.core.machine.RootShell
import app.toque.core.ui.Led
import app.toque.core.ui.LedWatch
import app.toque.core.ui.Leds
import app.toque.core.ui.RgbLeds
import app.toque.core.ui.RingColor
import app.toque.core.ui.RingColors
import app.toque.core.ui.RingIntensity
import app.toque.core.ui.RingLeds
import app.toque.core.ui.RingLook
import app.toque.core.ui.RingPolicy
import app.toque.core.ui.RingState
import java.util.concurrent.Executors

/**
 * Pilote l'anneau lumineux du bouton rotatif. Sur le Monsieur Cuisine Connect c'est une lumière
 * RVB (voyants red, green, blue), trouvée toute seule au démarrage ; sinon un voyant désigné dans
 * Réglages › Outils › Anneau lumineux. Couleur et force choisies dans Réglages (blanc à pleine
 * force par défaut, comme l'appli d'origine), allumé quand on se sert du robot selon [RingPolicy],
 * couleurs selon l'état si demandé (alerte : rouge qui clignote). N'écrit qu'au changement, et de
 * nouveau chaque minute au cas où le système l'aurait changé. Rien ne passe par la base moteur.
 */
class RingLight(
    private val prefs: Prefs,
    private val state: () -> RingState,
    private val screensaverShown: () -> Boolean,
    private val lastActivityAt: () -> Long,
) {
    /** Ce qu'il faut écrire : la composante 0–255 de chaque voyant, à la force [intensity]. */
    private data class Plan(val parts: List<Pair<String, Int>>, val intensity: String) {
        val key: String get() = parts.joinToString(",") { "${it.first}=${it.second}" } + "@" + intensity
    }

    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var applied: String? = null
    private var appliedAt = 0L
    private var blinking = false
    private var blinkOn = true
    private var previewUntil = 0L

    /** L'anneau RVB vient d'être trouvé (ou a disparu) : les réglages se redessinent. */
    var onFound: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            update()
            main.postDelayed(this, 5_000)
        }
    }

    private val blink = object : Runnable {
        override fun run() {
            if (!running || !blinking) return
            blinkOn = !blinkOn
            update()
            main.postDelayed(this, 500)
        }
    }

    fun start() {
        if (running) return
        running = true
        applied = null
        main.post(tick)
        find()
    }

    /**
     * Toque passe au second plan : une autre appli devant ([otherApp], l'appli d'origine par
     * exemple) reprend l'anneau blanc à pleine force, comme elle l'a toujours eu ; écran éteint, il
     * suit le réglage (éteint sauf « toujours allumé », dans sa couleur).
     */
    fun stop(otherApp: Boolean) {
        running = false
        main.removeCallbacks(tick)
        stopBlinking()
        val rgb = RgbLeds.decode(prefs.ringRgb)
        val single = single()
        if (rgb == null && single == null) return
        val color = if (otherApp) RingColors.WHITE else RingColors.of(prefs.ringColor)
        val on = otherApp || RingPolicy.mode(prefs.ringMode) == RingPolicy.ALWAYS_ON
        write(plan(rgb, single, color, on, if (otherApp) RingIntensity.HIGH else prefs.ringIntensity))
    }

    /** L'anneau ou un réglage a changé : on réapplique tout de suite. */
    fun reset() {
        applied = null
        update()
    }

    /** Montre la couleur choisie quelques secondes, même si l'anneau devrait être éteint (choix dans Réglages). */
    fun preview() {
        previewUntil = SystemClock.elapsedRealtime() + 4_000
        reset()
        main.postDelayed({ reset() }, 4_100)
    }

    /** À appeler à chaque geste, changement d'écran de veille ou de machine : ne fait rien si rien ne change. */
    fun update() {
        if (!running) return
        val rgb = RgbLeds.decode(prefs.ringRgb)
        val single = single()
        if (rgb == null && single == null) {
            stopBlinking()
            return
        }
        val saver = ScreensaverConfig.decode(prefs.screensaver)
        val limit = RingPolicy.idleLimitMs(saver.mode == ScreensaverConfig.OFF, saver.delayMinutes)
        val now = SystemClock.elapsedRealtime()
        var look = RingPolicy.look(prefs.ringMode, prefs.ringColor, prefs.ringByState, state(), screensaverShown(), now - lastActivityAt(), limit)
        if (now < previewUntil) look = RingLook(true, RingColors.of(prefs.ringColor))
        if (look.blink) startBlinking() else stopBlinking()
        val plan = plan(rgb, single, look.color, look.on && (!look.blink || blinkOn), prefs.ringIntensity)
        if (plan.key == applied && now - appliedAt < 60_000) return
        write(plan)
    }

    private fun single(): String? = prefs.ringLed.takeIf { Leds.validName(it) && !Leds.isScreen(it) }

    private fun plan(rgb: RgbLeds?, single: String?, color: RingColor, on: Boolean, intensity: String): Plan = when {
        rgb != null -> Plan(
            listOf(rgb.red to (if (on) color.r else 0), rgb.green to (if (on) color.g else 0), rgb.blue to (if (on) color.b else 0)),
            intensity,
        )
        // Un seul voyant : allumé ou éteint, à la force choisie.
        else -> Plan(listOf(single!! to (if (on) 255 else 0)), intensity)
    }

    private fun startBlinking() {
        if (blinking) return
        blinking = true
        blinkOn = true
        main.postDelayed(blink, 500)
    }

    private fun stopBlinking() {
        if (!blinking) return
        blinking = false
        blinkOn = true
        main.removeCallbacks(blink)
    }

    private fun write(plan: Plan) {
        applied = plan.key
        appliedAt = SystemClock.elapsedRealtime()
        // Pendant l'observation de l'appli d'origine, Toque ne touche à rien (elle fausserait le relevé).
        if (RingWatch.running) return
        writer.execute {
            val cmds = plan.parts.mapNotNull { (name, component) -> Leds.setCommand(name, RingPolicy.level(component, plan.intensity, maxBrightness(name))) }
            if (cmds.isNotEmpty()) RootShell.run(cmds.joinToString("; "), 5_000)
        }
    }

    /**
     * Cherche l'anneau RVB une fois par démarrage : garde d'abord l'état d'origine des voyants (pour
     * « Réinitialiser l'anneau »), puis le jeu trouvé. Un voyant désigné seul en 1.0.8 (« blue »)
     * laisse la place à l'anneau entier, sinon les couleurs partent au hasard.
     */
    private fun find() {
        if (searched) return
        searched = true
        writer.execute {
            val leds = list()
            if (prefs.ringOriginal.isEmpty() && leds.isNotEmpty()) prefs.ringOriginal = Leds.encodeOriginal(leds)
            val rgb = RingLeds.detect(leds.map { it.name })
            when {
                rgb != null -> {
                    prefs.ringRgb = rgb.encode()
                    if (prefs.ringLed.isNotEmpty()) prefs.ringLed = ""
                }
                leds.isNotEmpty() -> prefs.ringRgb = ""
            }
            main.post {
                applied = null
                update()
                onFound?.invoke()
            }
        }
    }

    companion object {
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-anneau").apply { isDaemon = true } }
        private val maxCache = HashMap<String, Int>()
        @Volatile private var searched = false

        /** Luminosité maximale du voyant (lue une fois). */
        fun maxBrightness(name: String): Int = synchronized(maxCache) {
            maxCache.getOrPut(name) {
                RootShell.run("cat ${Leds.ROOT}/$name/max_brightness", 5_000).out.trim().toIntOrNull()?.takeIf { it in 1..100_000 } ?: 255
            }
        }

        /** Les voyants de la tablette (session root ; liste vide si aucun ou sans root). */
        fun list(): List<Led> = Leds.parseList(RootShell.run(Leds.LIST_COMMAND, 8_000).out)

        /** Allume ([lit]) ou éteint un voyant, tout de suite (outil de recherche). */
        fun set(name: String, lit: Boolean): Boolean {
            val cmd = Leds.setCommand(name, if (lit) maxBrightness(name) else 0) ?: return false
            return RootShell.run(cmd, 5_000).ok
        }

        /**
         * « Réinitialiser l'anneau » (hors fil principal) : l'anneau RVB revient au blanc à pleine
         * force, comme à l'origine ; les autres voyants reprennent l'état gardé avant que Toque y
         * touche ; un voyant désigné seul est rallumé. Puis les réglages par défaut : blanc, forte,
         * « quand on se sert du robot », sans couleurs selon l'état.
         */
        fun resetAll(prefs: Prefs): Boolean {
            val leds = list()
            val rgb = RgbLeds.decode(prefs.ringRgb) ?: RingLeds.detect(leds.map { it.name })
            val original = Leds.decodeOriginal(prefs.ringOriginal)
            val cmds = ArrayList<String>()
            rgb?.names?.forEach { n -> Leds.setCommand(n, maxBrightness(n))?.let(cmds::add) }
            for (o in original) {
                if (rgb != null && o.name in rgb.names) continue
                Leds.restoreCommand(o.name, o.brightness, o.trigger)?.let(cmds::add)
            }
            val single = prefs.ringLed
            if (Leds.validName(single) && !Leds.isScreen(single) && original.none { it.name == single } && (rgb == null || single !in rgb.names)) {
                Leds.setCommand(single, maxBrightness(single))?.let(cmds::add)
            }
            prefs.ringLed = ""
            prefs.ringMode = RingPolicy.AUTO
            prefs.ringColor = RingColors.WHITE.key
            prefs.ringIntensity = RingIntensity.HIGH
            prefs.ringByState = false
            prefs.ringRgb = rgb?.encode().orEmpty()
            return cmds.isEmpty() || RootShell.run(cmds.joinToString("; "), 8_000).ok
        }
    }
}

/**
 * Observation des voyants pendant que l'appli d'origine tourne : toutes les ~400 ms pendant
 * 3 minutes au plus, en une seule session root. Les changements vont au journal (« anneau ») et
 * restent en mémoire pour l'écran Anneau lumineux, qui les montre au retour.
 */
object RingWatch {
    @Volatile var running = false
        private set
    /** Une observation s'est terminée depuis le démarrage de Toque. */
    @Volatile var finished = false
        private set
    @Volatile private var stopFlag = false
    private var watch = LedWatch()
    private var startedAt = 0L

    /** Démarre l'observation ([durationMs] au plus) ; faux si elle tourne déjà. */
    fun start(log: Logbook, durationMs: Long = 180_000): Boolean {
        if (running) return false
        running = true
        stopFlag = false
        synchronized(this) { watch = LedWatch() }
        startedAt = SystemClock.elapsedRealtime()
        log.write("anneau", "observation des voyants : début")
        val samples = (durationMs / 400).toInt()
        Thread({
            try {
                RootShell.stream(Leds.watchCommand(samples), durationMs + 20_000, { stopFlag }) { line ->
                    val (name, value) = Leds.parseSample(line) ?: return@stream
                    val at = SystemClock.elapsedRealtime() - startedAt
                    val change = synchronized(this) { watch.sample(name, value, at) }
                    if (change != null) log.write("anneau", "${change.name} : ${change.from} → ${change.to} à ${at / 100 / 10.0} s")
                }
            } finally {
                log.write("anneau", "observation des voyants : fin")
                finished = true
                running = false
            }
        }, "toque-anneau-observation").apply { isDaemon = true }.start()
        return true
    }

    fun stop() {
        stopFlag = true
    }

    fun changes(): List<LedWatch.Change> = synchronized(this) { watch.changes() }

    fun moved(): List<Pair<String, Int>> = synchronized(this) { watch.moved() }
}
