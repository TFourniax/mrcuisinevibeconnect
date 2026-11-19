package app.toque.core.foyer

import app.toque.core.json.Json
import app.toque.core.json.int
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str

/**
 * Un fichier de l'écran de veille, ajouté par le foyer dans Foyer (rien n'est fourni avec
 * Toque, dont le code est public). [version] change quand le fichier change.
 */
data class ScreensaverItem(val id: String, val kind: String, val url: String, val version: String) {
    /** Nom du fichier sur le robot, sans extension. */
    val stem: String get() = "$id-$version"
}

/**
 * Réglages de l'écran de veille, choisis dans Foyer › Réglages › Monsieur Cuisine :
 * l'horloge, les animations du foyer, ou rien ; après combien de minutes sans personne ;
 * combien de secondes par animation.
 */
data class ScreensaverConfig(val mode: String, val delayMinutes: Int, val itemSeconds: Int, val items: List<ScreensaverItem>) {
    companion object {
        const val OFF = "off"
        const val CLOCK = "clock"
        const val MEDIA = "media"
        val KINDS = setOf("gif", "video", "image")

        /** Sans réglage de Foyer (robot pas relié, Foyer plus ancien) : l'horloge après 5 minutes. */
        val DEFAULT = ScreensaverConfig(CLOCK, 5, 20, emptyList())

        private val ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val VERSION = Regex("^[0-9a-f]{1,16}$")

        /** Le bloc « screensaver » du contenu envoyé par Foyer ; les entrées douteuses sont écartées. */
        fun fromMap(m: Map<String, Any?>): ScreensaverConfig {
            val mode = when (m.str("mode")) {
                OFF -> OFF
                MEDIA -> MEDIA
                else -> CLOCK
            }
            val items = m["items"].list().mapNotNull { any ->
                val i = any.obj()
                val id = i.str("id")?.takeIf { ID.matches(it) } ?: return@mapNotNull null
                val kind = i.str("kind")?.takeIf { it in KINDS } ?: return@mapNotNull null
                val url = i.str("url")?.takeIf { it.startsWith("https://") && it.length <= 1000 } ?: return@mapNotNull null
                val version = Regex("[?&]v=([0-9a-f]{1,16})(?:&|$)").find(url)?.groupValues?.get(1)?.takeIf { VERSION.matches(it) } ?: "0"
                ScreensaverItem(id.lowercase(), kind, url, version)
            }.distinctBy { it.id }.take(60)
            return ScreensaverConfig(
                mode = mode,
                delayMinutes = (m.int("delayMinutes") ?: 5).coerceIn(1, 60),
                itemSeconds = (m.int("itemSeconds") ?: 20).coerceIn(5, 300),
                items = items,
            )
        }

        fun encode(c: ScreensaverConfig): String = Json.write(linkedMapOf(
            "mode" to c.mode,
            "delayMinutes" to c.delayMinutes,
            "itemSeconds" to c.itemSeconds,
            "items" to c.items.map { linkedMapOf("id" to it.id, "kind" to it.kind, "url" to it.url) },
        ))

        fun decode(text: String?): ScreensaverConfig = try {
            if (text.isNullOrBlank()) DEFAULT else fromMap(Json.parse(text).obj())
        } catch (_: Exception) {
            DEFAULT
        }
    }
}

/** Reconnaît un fichier d'écran de veille à ses premiers octets (16 suffisent), quel que soit son nom. */
object MediaSniff {
    /** « gif », « mp4 », « jpg », « png » ou « webp » ; null pour tout le reste. */
    fun ext(b: ByteArray): String? {
        fun at(i: Int, vararg v: Int) = b.size >= i + v.size && v.withIndex().all { (k, x) -> (b[i + k].toInt() and 0xFF) == x }
        return when {
            at(0, 0x47, 0x49, 0x46, 0x38, 0x37, 0x61) || at(0, 0x47, 0x49, 0x46, 0x38, 0x39, 0x61) -> "gif"
            at(4, 0x66, 0x74, 0x79, 0x70) -> "mp4"
            at(0, 0xFF, 0xD8, 0xFF) -> "jpg"
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "png"
            at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "webp"
            else -> null
        }
    }

    /** Le type annoncé par Foyer et le contenu reçu vont ensemble (une vidéo n'est pas lue comme une image). */
    fun fits(kind: String, ext: String?): Boolean = when (kind) {
        "gif" -> ext == "gif"
        "video" -> ext == "mp4"
        "image" -> ext == "jpg" || ext == "png" || ext == "webp"
        else -> false
    }
}
