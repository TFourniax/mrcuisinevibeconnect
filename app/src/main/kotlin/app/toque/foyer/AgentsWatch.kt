package app.toque.foyer

import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import app.toque.TimeSync
import app.toque.ToqueApp
import app.toque.core.foyer.AgentRun
import app.toque.core.foyer.Agents
import app.toque.core.json.Json
import java.util.Calendar

/**
 * Le statut des agents de la maison (Claude Code, Codex, Hermès…), lu dans la base de Foyer avec
 * le secret du robot : seulement une fois relié, et seulement les agents de son foyer. Toutes
 * les 20 s quand un agent tourne, toutes les minutes sinon (5 min la nuit), 10 s carte ouverte.
 * La dernière réponse est gardée pour s'afficher dès le démarrage ; elle part avec le lien.
 */
class AgentsWatch private constructor(private val app: ToqueApp) {
    private val prefs = app.prefs
    private val worker = HandlerThread("toque-agents").apply { start() }
    private val bg = Handler(worker.looper)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()
    private val debug = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val userAgent = "Toque/" + (try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }) + " (Android)"

    /** Dernière liste reçue de Foyer (fil principal en lecture). */
    @Volatile var runs: List<AgentRun> = Agents.decode(prefs.agentsCache)
        private set

    /** Dernière réponse de Foyer, et dernier échec depuis (0 : aucun). */
    @Volatile var lastOkMs: Long = prefs.agentsLastOkMs
        private set
    @Volatile var failingSinceMs: Long = 0L
        private set

    @Volatile private var open = false
    private val loopTask = Runnable { loop() }

    private fun start() {
        bg.postDelayed(loopTask, 4_000)
    }

    fun onChange(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    private fun changed() = main.post { for (l in ArrayList(listeners)) l() }

    /** La carte s'ouvre (on demande tout de suite, puis toutes les 10 s) ou se ferme. */
    fun opened(on: Boolean) {
        open = on
        if (on) {
            prefs.agentsSeenMs = System.currentTimeMillis()
            changed()
            bg.removeCallbacks(loopTask)
            bg.post(loopTask)
        }
    }

    private fun loop() {
        bg.removeCallbacks(loopTask)
        val foyer = Foyer.of(app)
        val next = when {
            !foyer.linked -> {
                // Plus relié : les agents de l'ancien foyer ne restent pas affichés.
                if (runs.isNotEmpty()) store(emptyList())
                60_000L
            }
            !prefs.foyerEnabled -> 10 * 60_000L
            !TimeSync.clockPlausible() -> 20_000L
            else -> try {
                poll(foyer)
            } catch (e: Exception) {
                failed("${e.javaClass.simpleName} ${e.message.orEmpty()}")
                if (open) 15_000L else 2 * 60_000L
            }
        }
        bg.postDelayed(loopTask, next)
    }

    private fun poll(foyer: Foyer): Long {
        val secret = foyer.existingSecret() ?: return 60_000L
        val r = FoyerHttp.rpc("robot_agents", Json.write(linkedMapOf("p_secret" to secret)), userAgent)
        if (debug) Log.i(TAG, "poll|${r.code}")
        if (r.code != 200) {
            failed("réponse ${r.code}")
            return if (open) 15_000L else 2 * 60_000L
        }
        val list = Agents.parse(r.body)
        lastOkMs = System.currentTimeMillis()
        prefs.agentsLastOkMs = lastOkMs
        failingSinceMs = 0L
        store(list)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return Agents.pollDelay(list, System.currentTimeMillis(), open, night = hour >= 23 || hour < 6)
    }

    private fun store(list: List<AgentRun>) {
        val changedList = list != runs
        runs = list
        if (changedList) prefs.agentsCache = Agents.encode(list)
        changed()
    }

    private fun failed(why: String) {
        if (failingSinceMs == 0L) {
            failingSinceMs = System.currentTimeMillis()
            app.log.write("agents", "Foyer injoignable : $why")
        }
        changed()
    }

    /**
     * Version de développement seulement : une question à Foyer avec un faux secret, pour vérifier
     * depuis l'émulateur Android 6 que la connexion sécurisée à la base aboutit (réponse 200, liste
     * vide). Le parcours d'interface la lance et lit le journal.
     */
    fun probe() {
        if (!debug) return
        bg.post {
            val line = try {
                val r = FoyerHttp.rpc("robot_agents", Json.write(linkedMapOf("p_secret" to "toque-ui-check-probe-0000000000000000000000")), userAgent)
                "probe|${r.code}|${r.body.take(40).replace('\n', ' ')}"
            } catch (e: Exception) {
                "probe|error|${e.javaClass.simpleName}|${e.message.orEmpty().take(160)}|${e.cause?.message.orEmpty().take(160)}"
            }
            Log.i(TAG, line)
        }
    }

    companion object {
        private const val TAG = "ToqueAgents"
        @Volatile private var instance: AgentsWatch? = null

        /** Démarré une fois par le processus de l'interface (ToqueApp). */
        fun start(app: ToqueApp): AgentsWatch = synchronized(this) {
            instance ?: AgentsWatch(app).also {
                instance = it
                it.start()
            }
        }

        fun of(app: ToqueApp): AgentsWatch = start(app)
    }
}
