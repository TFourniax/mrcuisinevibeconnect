package app.toque

import app.toque.core.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** GET minimal (heure, avis) : corps, et heure du serveur (en-tête Date, 0 si absente). */
object Http {
    class Response(val body: String, val serverMs: Long)

    /** Réponse refusée ; l'heure du serveur reste utile pour régler l'horloge du robot. */
    class Failure(message: String, val serverMs: Long) : IOException(message)

    var userAgent = "Toque (Android; +https://github.com/TFourniax/mrcuisinevibeconnect)"

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000, maxBytes: Int = 1024 * 1024): Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val code = c.responseCode
            val date = c.date
            if (code != 200) throw Failure(tr("réponse %d du serveur", code), date)
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException(tr("réponse trop grande"))
                }
                return Response(String(out.toByteArray(), Charsets.UTF_8), date)
            }
        } finally {
            c.disconnect()
        }
    }

    /** Heure d'un serveur d'après son en-tête Date, sans suivre de redirection (0 si absente). */
    fun serverTime(url: String, timeoutMs: Int = 8_000): Long {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "HEAD"
            c.setRequestProperty("User-Agent", userAgent)
            c.responseCode
            return c.date
        } finally {
            c.disconnect()
        }
    }
}
