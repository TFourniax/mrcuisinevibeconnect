package app.toque.core.foyer

import app.toque.core.json.Json
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str

/**
 * Un évènement à signaler dans « À savoir » (la cloche du robot) : nouvelles recettes du foyer,
 * menu changé, animations de l'écran de veille. [kind] dit lequel, [items] ce qui a changé
 * (titres, repas) ; le texte est écrit par l'interface, dans sa langue.
 */
data class NoticeEvent(
    val key: String,
    val kind: String,
    val items: List<String>,
    val atMs: Long,
    val read: Boolean = false,
)

/** Le journal des évènements, gardé dans les réglages (JSON), sans Android. */
object NoticeLog {
    const val KEEP_MS = 3L * 24 * 3600 * 1000
    const val MAX = 20

    fun decode(json: String?): List<NoticeEvent> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            Json.parse(json).list().mapNotNull { any ->
                val o = any.obj()
                val key = o.str("key") ?: return@mapNotNull null
                NoticeEvent(
                    key = key,
                    kind = o.str("kind").orEmpty(),
                    items = o["items"].list().mapNotNull { it as? String },
                    atMs = (o["at"] as? Number)?.toLong() ?: 0L,
                    read = o["read"] == true,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(events: List<NoticeEvent>): String = Json.write(events.map {
        linkedMapOf("key" to it.key, "kind" to it.kind, "items" to it.items, "at" to it.atMs, "read" to it.read)
    })

    /**
     * Ajoute [e] en tête (non lu). Un évènement de même clé est remplacé ; les plus vieux que
     * [KEEP_MS] s'en vont, et on n'en garde que [MAX].
     */
    fun add(events: List<NoticeEvent>, e: NoticeEvent, nowMs: Long): List<NoticeEvent> =
        prune(listOf(e) + events.filter { it.key != e.key }, nowMs)

    /**
     * Garde les [MAX] plus récents de moins de [KEEP_MS]. Un évènement « dans le futur » reste :
     * le robot n'a pas de pile d'horloge et se croit en 2010 jusqu'à la mise à l'heure.
     */
    fun prune(events: List<NoticeEvent>, nowMs: Long): List<NoticeEvent> =
        events.filter { nowMs - it.atMs < KEEP_MS }.take(MAX)

    /** Marque lu l'évènement [key], ou tous si [key] est null. */
    fun markRead(events: List<NoticeEvent>, key: String? = null): List<NoticeEvent> =
        events.map { if (key == null || it.key == key) it.copy(read = true) else it }

    fun unread(events: List<NoticeEvent>): Int = events.count { !it.read }
}

/** Ce qui a changé dans ce que Foyer envoie au robot, d'une synchro à l'autre. */
object FeedChanges {
    /** Les éléments de [current] absents de [known], dans l'ordre de [current]. */
    fun added(known: Collection<String>, current: List<String>): List<String> {
        val set = HashSet(known)
        return current.filter { it !in set }
    }

    /**
     * Les repas à venir (jour ≥ [today]) nouveaux ou modifiés par rapport à [known] (même jour,
     * même heure et même plat : inchangé).
     */
    fun changedPlans(known: List<FoyerPlan>, current: List<FoyerPlan>, today: String): List<FoyerPlan> {
        val seen = HashSet<String>()
        for (p in known) seen += signature(p)
        return current.filter { it.day >= today && signature(it) !in seen }
    }

    private fun signature(p: FoyerPlan): String = p.day + "|" + (p.time ?: "") + "|" + p.title.trim().lowercase()
}
