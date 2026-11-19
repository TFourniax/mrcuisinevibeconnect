package app.toque

import android.content.Context
import android.os.SystemClock
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.time.ZoneLookup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Date et heure du robot, réglées toutes seules :
 *  - le fuseau horaire est trouvé d'après la connexion Internet (géolocalisation de l'adresse IP),
 *    ou choisi à la main dans les réglages, puis appliqué au système (root) ;
 *  - l'heure vient du réseau (NTP d'Android, activé ici) ; si elle est encore fausse de plus de
 *    deux minutes, elle est corrigée d'après l'en-tête Date des serveurs interrogés (même une
 *    réponse refusée, et à défaut celle de Foyer, sans suivre sa redirection).
 * Le robot n'a pas de pile d'horloge : au démarrage il se croit en 1970 ou en 2010, et rien de
 * sécurisé (Foyer, les photos) ne marche avant que l'heure soit réglée.
 */
class TimeSync(private val context: Context, private val prefs: Prefs, private val log: Logbook) {
    class Result(val ok: Boolean, val zone: String, val message: String)

    fun run(): Result {
        val wanted = prefs.timeZone
        var zone: String? = null
        var source = tr("choix manuel")
        var serverMs = 0L
        if (wanted == ZoneLookup.AUTO) {
            for (p in ZoneLookup.PROVIDERS) {
                try {
                    val (body, date) = get(p.url)
                    if (date > 0) serverMs = date
                    val z = ZoneLookup.zoneOf(body)
                    if (z != null && z in available) {
                        zone = z
                        source = p.name
                        break
                    }
                    log.write("heure", "${p.name} : pas de fuseau dans la réponse")
                } catch (e: Http.Failure) {
                    if (e.serverMs > 0) serverMs = e.serverMs
                    log.write("heure", "${p.name} : ${e.message}")
                } catch (e: Exception) {
                    log.write("heure", "${p.name} : ${e.message ?: e.toString()}")
                }
            }
        } else {
            zone = wanted.takeIf { it in available } ?: return Result(false, current(), tr("Fuseau horaire inconnu : %s", wanted))
        }
        // Heure seule, quand l'horloge est fausse et qu'aucun service n'a donné la sienne.
        if (serverMs < MIN_PLAUSIBLE_MS && !clockPlausible()) {
            serverMs = try {
                Http.serverTime(TIME_ONLY_URL)
            } catch (e: Exception) {
                log.write("heure", "foyer : ${e.message ?: e.toString()}")
                0L
            }
        }
        if (!RootShell.available()) return Result(false, current(), tr("Accès root nécessaire pour régler l'heure du robot."))

        // Heure réseau d'Android (NTP), au cas où elle aurait été désactivée.
        RootShell.run("settings put global auto_time 1")
        // L'heure d'abord, fuseau trouvé ou non : sans elle, rien de sécurisé ne marche.
        val fixed = fixClock(serverMs)
        val z = zone ?: return Result(false, current(),
            if (clockPlausible()) tr("Fuseau horaire introuvable : vérifie le Wi-Fi.") else tr("Heure introuvable : vérifie le Wi-Fi."))
        if (z != current()) {
            val r = RootShell.run("service call alarm 3 s16 $z")
            if (!r.ok || prop() != z) {
                // Repli : propriété système puis annonce du changement aux applis.
                RootShell.run("setprop persist.sys.timezone $z && am broadcast -a android.intent.action.TIMEZONE_CHANGED --es time-zone $z")
            }
            TimeZone.setDefault(null)
            log.write("heure", "fuseau $z ($source)")
        }
        val zoneOk = prop() == z || current() == z
        prefs.lastZone = z
        val now = clock()
        return when {
            !zoneOk -> Result(false, current(), tr("Le système a refusé le fuseau %s", z))
            // Fuseau en place mais horloge toujours fausse : pas fini, on réessaie bientôt.
            !clockPlausible() -> Result(false, z, tr("Heure introuvable : vérifie le Wi-Fi."))
            fixed -> Result(true, z, tr("Heure corrigée : %s (%s)", now, z))
            else -> Result(true, z, tr("Il est %s (%s)", now, z))
        }
    }

    /** Corrige l'horloge si elle s'écarte de plus de 2 min de l'heure donnée par un serveur. */
    private fun fixClock(serverMs: Long): Boolean {
        if (serverMs < MIN_PLAUSIBLE_MS) return false
        if (Math.abs(System.currentTimeMillis() - serverMs) < 120_000L) return false
        val r = RootShell.run("service call alarm 2 i64 $serverMs")
        if (Math.abs(System.currentTimeMillis() - serverMs) > 60_000L) {
            val utc = SimpleDateFormat("MMddHHmmyyyy.ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            RootShell.run("date -u ${utc.format(Date(serverMs))}")
        }
        val ok = Math.abs(System.currentTimeMillis() - serverMs) < 60_000L
        log.write("heure", if (ok) "horloge corrigée d'après le réseau" else "correction de l'horloge refusée (${r.err.trim()})")
        return ok
    }

    private fun get(url: String): Pair<String, Long> {
        val r = Http.get(url, mapOf("Accept" to "application/json"), timeoutMs = 10_000, maxBytes = 64 * 1024)
        return r.body to r.serverMs
    }

    private fun prop(): String = RootShell.run("getprop persist.sys.timezone").out.trim()

    companion object {
        /** 1er janvier 2025 : en dessous, l'heure d'un serveur (ou du robot) n'est pas crédible. */
        private const val MIN_PLAUSIBLE_MS = 1_735_689_600_000L
        /** Heure seule : la redirection http → https de Foyer porte un en-tête Date. */
        private const val TIME_ONLY_URL = "http://foyer-life.netlify.app/"
        private val available: Set<String> by lazy { TimeZone.getAvailableIDs().toHashSet() }

        fun current(): String = TimeZone.getDefault().id

        fun clock(): String = SimpleDateFormat("HH:mm", Locale.FRANCE).apply { timeZone = TimeZone.getDefault() }.format(Date())

        /** L'horloge du robot donne une date crédible (après le 1er janvier 2025). */
        fun clockPlausible(): Boolean = System.currentTimeMillis() >= MIN_PLAUSIBLE_MS

        @Volatile private var started = false
        private val wake = Semaphore(0)
        @Volatile private var lastKick = 0L

        /** Réglage tout de suite (Foyer refuse la connexion à cause de l'heure) ; une fois par 30 s au plus. */
        fun kick() {
            val now = SystemClock.elapsedRealtime()
            if (lastKick != 0L && now - lastKick < 30_000L) return
            lastKick = now
            wake.release()
        }

        /** Attend [ms] (horloge monotone : la remise à l'heure ne la fausse pas), ou un [kick]. */
        private fun pause(ms: Long) {
            wake.tryAcquire(ms, TimeUnit.MILLISECONDS)
            wake.drainPermits()
        }

        /**
         * Au lancement de l'interface : réglage dès que le réseau répond (le Wi-Fi met un moment à
         * se connecter au démarrage du robot), puis toutes les 12 heures. Tant que l'horloge est
         * fausse : 5 s, 15 s, 30 s, 1 min, puis toutes les 2 min.
         */
        fun start(context: Context, prefs: Prefs, log: Logbook) {
            if (started) return
            started = true
            val t = Thread {
                val quick = longArrayOf(5_000, 15_000, 30_000, 60_000, 120_000)
                val retries = longArrayOf(5_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000)
                var i = 0
                while (true) {
                    val r = try {
                        TimeSync(context, prefs, log).run()
                    } catch (e: Throwable) {
                        log.write("heure", "erreur : ${e.message}")
                        Result(false, current(), "")
                    }
                    val wait = when {
                        r.ok -> 12 * 3600_000L
                        !clockPlausible() -> quick[minOf(i, quick.size - 1)]
                        else -> retries[minOf(i, retries.size - 1)]
                    }
                    i = if (r.ok) 0 else i + 1
                    try {
                        pause(wait)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
            t.isDaemon = true
            t.name = "toque-heure"
            t.start()
        }
    }
}
