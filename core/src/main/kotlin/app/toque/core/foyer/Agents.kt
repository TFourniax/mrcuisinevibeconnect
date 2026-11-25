package app.toque.core.foyer

import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str

/**
 * Une session d'agent (Claude Code, Codex, Hermès, un harness maison…) telle que Foyer la
 * connaît : ce qu'elle fait et depuis quand. Heures en millisecondes depuis 1970.
 */
data class AgentRun(
    val agent: String,
    val session: String,
    /** « local », « cloud », ou vide si l'agent ne l'a pas dit. */
    val location: String,
    val state: String,
    val title: String,
    val project: String,
    /** Prénom de la personne à qui appartient l'agent. */
    val who: String,
    /** Début de l'état actuel. */
    val since: Long,
    /** Dernière nouvelle de l'agent. */
    val updated: Long,
)

/**
 * Le statut des agents sur le robot : une petite icône dans la barre du haut, une carte à la
 * demande. Ce qui se lit d'un coup d'œil pendant qu'on cuisine : un agent t'attend (une question,
 * une permission), un agent a fini (de quoi en relancer un), des agents tournent.
 */
object Agents {
    const val WORKING = "working"
    const val WAITING = "waiting"
    const val DONE = "done"
    const val ERROR = "error"
    const val IDLE = "idle"

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** Un « terminé » ou une erreur restent affichés 12 h. */
    const val KEEP_ENDED_MS = 12 * HOUR

    /** Sans nouvelle depuis 30 min, un agent « en cours » est dit silencieux. */
    const val STALE_MS = 30 * MINUTE

    /** Au plus autant de lignes dans la carte. */
    const val MAX_LINES = 6

    private val STATES = setOf(WORKING, WAITING, DONE, ERROR, IDLE)

    /** Réponse de Foyer (`robot_agents`) : un tableau JSON ; les lignes illisibles sont ignorées. */
    fun parse(body: String): List<AgentRun> {
        val rows = try {
            Json.parse(body).list()
        } catch (_: Exception) {
            return emptyList()
        }
        return rows.mapNotNull { row(it.obj(), sinceKey = "state_since", updatedKey = "updated_at", iso = true) }
    }

    /** Copie gardée sur le robot (affichée tout de suite au démarrage, avant la première réponse). */
    fun encode(runs: List<AgentRun>): String = Json.write(runs.map { r ->
        linkedMapOf<String, Any?>(
            "agent" to r.agent, "session_key" to r.session, "location" to r.location, "state" to r.state,
            "title" to r.title, "project" to r.project, "who" to r.who, "since" to r.since, "updated" to r.updated,
        )
    })

    fun decode(text: String): List<AgentRun> {
        if (text.isBlank()) return emptyList()
        val rows = try {
            Json.parse(text).list()
        } catch (_: Exception) {
            return emptyList()
        }
        return rows.mapNotNull { row(it.obj(), sinceKey = "since", updatedKey = "updated", iso = false) }
    }

    private fun row(m: Map<String, Any?>, sinceKey: String, updatedKey: String, iso: Boolean): AgentRun? {
        val agent = m.str("agent")?.trim()?.lowercase().orEmpty()
        val state = m.str("state")?.trim()?.lowercase().orEmpty()
        if (agent.isEmpty() || state !in STATES) return null
        fun time(key: String): Long? = if (iso) parseIso(m.str(key)) else (m[key] as? Number)?.toLong()
        val updated = time(updatedKey) ?: return null
        val since = time(sinceKey) ?: updated
        return AgentRun(
            agent = agent,
            session = m.str("session_key").orEmpty(),
            location = m.str("location")?.trim()?.lowercase().orEmpty().takeIf { it == "local" || it == "cloud" }.orEmpty(),
            state = state,
            title = clean(m.str("title"), 120),
            project = clean(m.str("project"), 60),
            who = clean(m.str("who"), 30),
            since = minOf(since, updated),
            updated = updated,
        )
    }

    private fun clean(s: String?, max: Int): String =
        s.orEmpty().replace(Regex("[\\u0000-\\u001F\\u007F]"), " ").replace(Regex("\\s+"), " ").trim().take(max)

    /** « 2026-11-25T18:16:09.123456+00:00 », « …Z » → ms depuis 1970 (null si illisible). */
    fun parseIso(s: String?): Long? {
        if (s == null) return null
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}(?::?\d{2})?)?$""").find(s.trim()) ?: return null
        val g = m.groupValues
        val y = g[1].toInt()
        val mo = g[2].toInt()
        val d = g[3].toInt()
        val h = g[4].toInt()
        val mi = g[5].toInt()
        val se = g[6].toInt()
        if (mo !in 1..12 || d !in 1..31 || h > 23 || mi > 59 || se > 60) return null
        val ms = g[7].take(3).padEnd(3, '0').toInt()
        val zone = g[8]
        val offsetMin = when {
            zone.isEmpty() || zone == "Z" -> 0
            else -> {
                val sign = if (zone[0] == '-') -1 else 1
                val digits = zone.substring(1).replace(":", "")
                sign * (digits.take(2).toInt() * 60 + (digits.drop(2).toIntOrNull() ?: 0))
            }
        }
        // Jours depuis 1970 (calendrier grégorien), sans java.time (absent d'Android 6).
        val yy = if (mo <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (mo + (if (mo > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468L
        return ((days * 24 + h) * 60 + mi - offsetMin) * 60_000L + se * 1000L + ms
    }

    /** Ce qui compte en premier : t'attend, erreur, en cours, terminé. */
    private fun rank(state: String): Int = when (state) {
        WAITING -> 0
        ERROR -> 1
        WORKING -> 2
        DONE -> 3
        else -> 4
    }

    /**
     * Les lignes de la carte : jamais un agent arrêté, un « terminé » ou une erreur 12 h au plus ;
     * les plus urgents d'abord, puis les plus récents ; [MAX_LINES] au plus.
     */
    fun shown(runs: List<AgentRun>, now: Long): List<AgentRun> = runs
        .filter { r ->
            when (r.state) {
                IDLE -> false
                DONE, ERROR -> now - r.since < KEEP_ENDED_MS
                else -> now - r.updated < 36 * HOUR
            }
        }
        .sortedWith(compareBy<AgentRun> { rank(it.state) }.thenByDescending { it.updated })
        .take(MAX_LINES)

    /** Un agent « en cours » qui ne donne plus de nouvelles (arrêté sans le dire, ou une très longue commande). */
    fun stale(r: AgentRun, now: Long): Boolean = r.state == WORKING && now - r.updated > STALE_MS

    /** La pastille de l'icône. */
    enum class Signal {
        /** Aucun agent récent : pas d'icône. */
        NONE,
        /** Des agents connus, rien de neuf : l'icône seule. */
        QUIET,
        /** Au moins un agent tourne. */
        WORKING,
        /** Un agent a fini depuis la dernière ouverture de la carte. */
        DONE,
        /** Un agent t'attend, ou une erreur pas encore vue. */
        NEEDS_YOU,
    }

    /** [seen] : dernière ouverture de la carte (un « terminé » ou une erreur vus ne colorent plus l'icône). */
    fun signal(runs: List<AgentRun>, now: Long, seen: Long): Signal {
        val lines = shown(runs, now)
        if (lines.isEmpty()) return Signal.NONE
        return when {
            // Une attente oubliée (session laissée ouverte la nuit) ne colore plus l'icône après 12 h.
            lines.any { (it.state == WAITING && now - it.since < KEEP_ENDED_MS) || (it.state == ERROR && it.since > seen) } -> Signal.NEEDS_YOU
            lines.any { it.state == DONE && it.since > seen } -> Signal.DONE
            lines.any { it.state == WORKING && !stale(it, now) } -> Signal.WORKING
            else -> Signal.QUIET
        }
    }

    /**
     * Prochaine question à Foyer : toutes les 10 s carte ouverte, 20 s quand un agent tourne ou
     * t'attend (nouvelles dans les 2 dernières heures), sinon toutes les minutes (5 min la nuit).
     */
    fun pollDelay(runs: List<AgentRun>, now: Long, open: Boolean, night: Boolean): Long = when {
        open -> 10_000L
        runs.any { (it.state == WORKING || it.state == WAITING) && now - it.updated < 2 * HOUR } -> 20_000L
        night -> 5 * MINUTE
        else -> MINUTE
    }

    /** « Claude Code », « Codex », « Hermès » ; sinon le nom donné, en clair. */
    fun name(agent: String): String = when (agent) {
        "claude", "claude-code", "claude_code", "claudecode" -> "Claude Code"
        "codex" -> "Codex"
        "hermes" -> "Hermès"
        "gemini" -> "Gemini"
        "cursor" -> "Cursor"
        "aider" -> "Aider"
        else -> agent.replace('-', ' ').replace('_', ' ').trim().replaceFirstChar { it.uppercaseChar() }
    }

    /** Le mot de l'état. */
    fun stateLabel(r: AgentRun, now: Long): String = when {
        stale(r, now) -> tr("Silencieux")
        r.state == WORKING -> tr("En cours")
        r.state == WAITING -> tr("T'attend")
        r.state == DONE -> tr("Terminé")
        r.state == ERROR -> tr("Erreur")
        else -> tr("Arrêté")
    }

    /** « 12 min », « 3 h », « 2 j ». */
    fun duration(ms: Long): String {
        val min = maxOf(1L, ms / MINUTE)
        return when {
            min < 60 -> tr("%d min", min.toInt())
            min < 24 * 60 -> tr("%d h", (min / 60).toInt())
            else -> tr("%d j", (min / (24 * 60)).toInt())
        }
    }

    /** « depuis 12 min », « il y a 3 h », « à l'instant », « aucune nouvelle depuis 40 min ». */
    fun timeLabel(r: AgentRun, now: Long): String {
        if (stale(r, now)) return tr("aucune nouvelle depuis %s", duration(now - r.updated))
        val d = now - r.since
        if (d < MINUTE) return tr("à l'instant")
        return when (r.state) {
            WORKING, WAITING -> tr("depuis %s", duration(d))
            else -> tr("il y a %s", duration(d))
        }
    }

    /** Sous le titre de la carte : « 1 t'attend · 2 en cours · 1 terminé ». */
    fun summary(lines: List<AgentRun>, now: Long): String {
        val waiting = lines.count { it.state == WAITING }
        val errors = lines.count { it.state == ERROR }
        val working = lines.count { it.state == WORKING && !stale(it, now) }
        val done = lines.count { it.state == DONE }
        val parts = ArrayList<String>()
        if (waiting > 0) parts += if (waiting > 1) tr("%d t'attendent", waiting) else tr("1 t'attend")
        if (errors > 0) parts += if (errors > 1) tr("%d erreurs", errors) else tr("1 erreur")
        if (working > 0) parts += tr("%d en cours", working)
        if (done > 0) parts += if (done > 1) tr("%d terminés", done) else tr("1 terminé")
        return if (parts.isEmpty()) tr("Rien en cours") else parts.joinToString(" · ")
    }

    /** Ce que lit un lecteur d'écran sur l'icône. */
    fun iconDescription(signal: Signal, lines: List<AgentRun>, now: Long): String = when (signal) {
        Signal.NONE -> tr("Agents")
        else -> tr("Agents : %s", summary(lines, now))
    }
}
