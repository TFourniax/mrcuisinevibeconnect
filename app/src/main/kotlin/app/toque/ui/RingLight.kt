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
import app.toque.core.ui.RingPolicy
import java.util.concurrent.Executors

/**
 * Pilote l'anneau lumineux du bouton rotatif, une fois désigné (Réglages › Outils › Anneau
 * lumineux) : allumé quand on se sert du robot, éteint après le délai de l'écran de veille
 * (ou 2 minutes sans écran de veille), selon [RingPolicy]. N'écrit qu'au changement, et de
 * nouveau chaque minute au cas où le système l'aurait rallumé. Rien ne passe par la base moteur.
 */
class RingLight(
    private val prefs: Prefs,
    private val busy: () -> Boolean,
    private val screensaverShown: () -> Boolean,
    private val lastActivityAt: () -> Long,
) {
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var applied: Boolean? = null
    private var appliedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            update()
            main.postDelayed(this, 5_000)
        }
    }

    fun start() {
        if (running) return
        running = true
        applied = null
        main.post(tick)
    }

    /**
     * Toque passe au second plan : une autre appli devant ([otherApp], l'appli d'origine par
     * exemple) reprend l'anneau allumé ; écran éteint, il suit le réglage (éteint sauf « toujours »).
     */
    fun stop(otherApp: Boolean) {
        running = false
        main.removeCallbacks(tick)
        val name = prefs.ringLed
        if (!Leds.validName(name) || Leds.isScreen(name)) return
        val lit = otherApp || RingPolicy.mode(prefs.ringMode) == RingPolicy.ALWAYS_ON
        write(name, lit)
    }

    /** L'anneau désigné ou le réglage a changé : on réapplique tout de suite. */
    fun reset() {
        applied = null
        update()
    }

    /** À appeler à chaque geste, changement d'écran de veille ou de machine : ne fait rien si rien ne change. */
    fun update() {
        if (!running) return
        val name = prefs.ringLed
        if (!Leds.validName(name) || Leds.isScreen(name)) return
        val saver = ScreensaverConfig.decode(prefs.screensaver)
        val limit = RingPolicy.idleLimitMs(saver.mode == ScreensaverConfig.OFF, saver.delayMinutes)
        val now = SystemClock.elapsedRealtime()
        val lit = RingPolicy.lit(prefs.ringMode, busy(), screensaverShown(), now - lastActivityAt(), limit)
        if (applied == lit && now - appliedAt < 60_000) return
        write(name, lit)
    }

    private fun write(name: String, lit: Boolean) {
        applied = lit
        appliedAt = SystemClock.elapsedRealtime()
        // Pendant l'observation de l'appli d'origine, Toque ne touche à rien (elle fausserait le relevé).
        if (RingWatch.running) return
        writer.execute {
            val value = if (lit) maxBrightness(name) else 0
            Leds.setCommand(name, value)?.let { RootShell.run(it, 5_000) }
        }
    }

    companion object {
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-anneau").apply { isDaemon = true } }
        private val maxCache = HashMap<String, Int>()

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
