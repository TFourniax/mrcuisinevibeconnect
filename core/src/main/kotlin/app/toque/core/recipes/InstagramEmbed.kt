package app.toque.core.recipes

import app.toque.core.json.Json

/**
 * Lecture d'un réel Instagram sur le robot. Sans compte, Instagram ne montre qu'une page de
 * connexion, mais chaque publication publique a une page « à intégrer » (celle que les sites
 * affichent) : elle donne souvent l'adresse de la vidéo et l'image de couverture. Ces adresses
 * sont signées et expirent : elles sont lues au moment de regarder, jamais gardées.
 */
object InstagramEmbed {
    /** Ce que la page donne : la vidéo (https) et l'image de couverture (https), ou rien. */
    data class Media(val video: String?, val poster: String?)

    private val CODE = Regex("""/(?:p|reels?|tv)/([A-Za-z0-9_-]{5,40})(?:/|$)""")

    /** Le code de la publication dans un lien https://www.instagram.com/(reel|reels|p|tv)/<code>/…, aussi derrière un nom. */
    fun shortcode(url: String): String? {
        val m = Regex("""^https://([A-Za-z0-9.-]+)(/[^?#]*)?""", RegexOption.IGNORE_CASE).find(url.trim()) ?: return null
        val host = m.groupValues[1].lowercase()
        if (host != "instagram.com" && !host.endsWith(".instagram.com")) return null
        return CODE.find(m.groupValues[2])?.groupValues?.get(1)
    }

    fun embedUrl(url: String): String? = shortcode(url)?.let { "https://www.instagram.com/p/$it/embed/captioned/" }

    /** Vidéo et couverture d'une page « à intégrer » ; null pour ce qui manque. */
    fun parse(html: String): Media {
        val video = https(jsonString(html, "video_url"))
        var poster = https(jsonString(html, "display_url")) ?: https(jsonString(html, "thumbnail_src"))
        if (poster == null) {
            val tag = Regex("""<img\b[^>]*class\s*=\s*["'][^"']*\bEmbeddedMediaImage\b[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value
            if (tag != null) {
                val srcset = attribute(tag, "srcset")
                poster = https(srcset?.let { largest(it) }) ?: https(attribute(tag, "src"))
            }
        }
        return Media(video, poster)
    }

    /** Une valeur de texte JSON trouvée dans un script ("video_url":"https:\/\/…"), décodée. */
    private fun jsonString(html: String, key: String): String? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){1,20000})\"").find(html) ?: return null
        return try {
            Json.parse("\"" + m.groupValues[1] + "\"") as? String
        } catch (_: Exception) {
            null
        }
    }

    private fun attribute(tag: String, name: String): String? {
        val m = Regex("""\s$name\s*=\s*"([^"]*)"|\s$name\s*=\s*'([^']*)'""", RegexOption.IGNORE_CASE).find(tag) ?: return null
        val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
        return raw.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").ifEmpty { null }
    }

    private fun largest(srcset: String): String? =
        srcset.split(',').map { it.trim().split(Regex("\\s+")) }
            .filter { it.isNotEmpty() && it[0].isNotEmpty() }
            .maxByOrNull { p -> p.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0 }?.get(0)

    private fun https(value: String?): String? = RecipeCodec.httpsOnly(value).ifEmpty { null }
}
