package app.toque.foyer

import android.content.Context
import android.util.Base64
import app.toque.core.foyer.MediaSniff
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTPS vers Foyer. Android 6 ne connaît pas les autorités racines récentes (ISRG Root X1…) :
 * les connexions de Toque vers Foyer, sa base (statut des agents) et les photos des recettes
 * font confiance aux racines du système plus celles embarquées (assets/certs/foyer-roots.pem).
 * Le nom du serveur reste vérifié normalement.
 */
object FoyerHttp {
    const val BASE = "https://foyer-life.netlify.app"

    /**
     * La base de données de Foyer, lue directement pour le statut des agents (sans passer par
     * Netlify). La clé « publishable » est publique par nature (les pages de Foyer la donnent à
     * tout navigateur) : ce que le robot obtient, c'est son secret qui le décide, côté base.
     */
    const val SUPABASE = "https://mhfrnaiurtgkoqraabfg.supabase.co"
    private const val SUPABASE_KEY = "sb_publishable_katLBV3zMZefGPURLFmSyw_G-5PUfvz"

    class Response(val code: Int, val body: String, val etag: String?)

    @Volatile private var factory: SSLSocketFactory? = null
    @Volatile private var ready = false

    fun init(context: Context) {
        if (ready) return
        factory = try {
            buildFactory(context)
        } catch (_: Exception) {
            null
        }
        ready = true
    }

    private fun buildFactory(context: Context): SSLSocketFactory {
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null, null)
        val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        system.init(null as KeyStore?)
        val systemTrust = system.trustManagers.filterIsInstance<X509TrustManager>().first()
        systemTrust.acceptedIssuers.forEachIndexed { i, cert -> store.setCertificateEntry("system-$i", cert) }
        val pem = context.assets.open("certs/foyer-roots.pem").use { it.readBytes().toString(Charsets.US_ASCII) }
        val certificates = CertificateFactory.getInstance("X.509")
        Regex("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+)-----END CERTIFICATE-----").findAll(pem).forEachIndexed { i, m ->
            val der = Base64.decode(m.groupValues[1].replace(Regex("\\s"), ""), Base64.DEFAULT)
            val cert = certificates.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            store.setCertificateEntry("foyer-$i", cert)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(store)
        val tls = SSLContext.getInstance("TLS")
        tls.init(null, trust.trustManagers, null)
        return tls.socketFactory
    }

    /**
     * Les connexions https de l'appli qui ne passent pas par [open] (le lecteur vidéo d'Android
     * lit ses flux dans ce processus) font aussi confiance aux racines embarquées : les racines du
     * système plus trois, jamais moins.
     */
    fun useAsDefault() {
        factory?.let { HttpsURLConnection.setDefaultSSLSocketFactory(it) }
    }

    fun isInstagram(host: String): Boolean = host.equals("instagram.com", ignoreCase = true) || host.endsWith(".instagram.com", ignoreCase = true)

    /**
     * La page publique « à intégrer » d'un réel, lue depuis la maison : https et instagram.com
     * seulement (redirections comprises, 3 au plus), jamais une adresse locale, 6 s, 3 Mo, et
     * jamais la clé du robot.
     */
    fun instagramPage(url: String, userAgent: String): String {
        val deadline = System.currentTimeMillis() + 6_000
        var target = URL(url)
        repeat(4) {
            if (target.protocol != "https" || !isInstagram(target.host)) throw IOException("instagram.com uniquement")
            if (InetAddress.getAllByName(target.host).any { isLocal(it) }) throw IOException("adresse locale refusée")
            val c = open(target.toString())
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 6_000
                c.readTimeout = 6_000
                c.setRequestProperty("User-Agent", userAgent)
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                c.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.5")
                val code = c.responseCode
                if (code in 300..399) {
                    target = URL(target, c.getHeaderField("Location") ?: throw IOException("redirection sans adresse"))
                    return@repeat
                }
                if (code != 200) throw IOException("réponse $code")
                return String(c.inputStream.use { readAll(it, 3 * 1024 * 1024, deadline) }, Charsets.UTF_8)
            } finally {
                c.disconnect()
            }
        }
        throw IOException("trop de redirections")
    }

    /** Une adresse de vidéo à lire : https, et aucune adresse du réseau local derrière son nom. */
    fun safeMediaUrl(url: String): Boolean = try {
        val u = URL(url)
        u.protocol == "https" && InetAddress.getAllByName(u.host).none { isLocal(it) }
    } catch (_: Exception) {
        false
    }

    private fun open(url: String): HttpsURLConnection {
        val c = URL(url).openConnection() as? HttpsURLConnection ?: throw IOException("https uniquement")
        factory?.let { c.sslSocketFactory = it }
        c.useCaches = false
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        return c
    }

    /** Appel à l'API du robot ([path] commence par « /api/robot/ »), avec le secret du robot. */
    fun call(method: String, path: String, secret: String, userAgent: String, body: String? = null, etag: String? = null): Response {
        val c = open(BASE + path)
        try {
            c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer $secret")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", userAgent)
            if (!etag.isNullOrEmpty()) c.setRequestProperty("If-None-Match", etag)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = when {
                code == 304 -> null
                code >= 400 -> c.errorStream
                else -> c.inputStream
            }
            val text = stream?.use { String(readAll(it, 8 * 1024 * 1024), Charsets.UTF_8) }.orEmpty()
            return Response(code, text, c.getHeaderField("ETag"))
        } finally {
            c.disconnect()
        }
    }

    /**
     * Une fonction de la base de Foyer ([name] : lettres et « _ »), appelée en POST avec [body]
     * (JSON). Le secret du robot voyage dans le corps, jamais dans l'adresse. 10 s, 256 Ko.
     */
    fun rpc(name: String, body: String, userAgent: String): Response {
        if (!Regex("[a-z_]{1,40}").matches(name)) throw IOException("fonction inconnue")
        val c = open("$SUPABASE/rest/v1/rpc/$name")
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.requestMethod = "POST"
            c.setRequestProperty("apikey", SUPABASE_KEY)
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("User-Agent", userAgent)
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.use { String(readAll(it, 256 * 1024, System.currentTimeMillis() + 10_000), Charsets.UTF_8) }.orEmpty()
            return Response(code, text, null)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Un fichier gardé par Foyer lui-même pour le robot : https, l'hôte exact de Foyer, et le
     * chemin des photos de recettes ou de l'écran de veille. Seul cas où la clé du robot part
     * avec un téléchargement.
     */
    fun isFoyerFile(url: String): Boolean = try {
        val u = URL(url)
        val base = URL(BASE)
        u.protocol == "https" && u.host.equals(base.host, ignoreCase = true) && (u.port == -1 || u.port == 443) &&
            (u.path.startsWith("/api/robot/photo/") || u.path.startsWith("/api/robot/screensaver/"))
    } catch (_: Exception) {
        false
    }

    /**
     * Une photo de recette : https seulement, jamais une adresse du réseau local (le robot est
     * chez toi), redirections vérifiées une à une (3 au plus), 20 s en tout, 4 Mo au plus, et
     * seulement du JPEG, du PNG ou du WebP reconnus à leurs premiers octets : rien d'autre
     * n'arrive au décodeur d'images d'Android 6. [secret] (la clé du robot) n'accompagne que la
     * première requête, et seulement vers une photo gardée par Foyer : jamais une redirection,
     * jamais un autre site.
     */
    fun download(url: String, userAgent: String, secret: String? = null): ByteArray {
        val deadline = System.currentTimeMillis() + 20_000
        var target = URL(url)
        var hop = 0
        repeat(4) {
            if (target.protocol != "https") throw IOException("https uniquement")
            if (InetAddress.getAllByName(target.host).any { isLocal(it) }) throw IOException("adresse locale refusée")
            val c = open(target.toString())
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 8_000
                c.readTimeout = 8_000
                c.setRequestProperty("User-Agent", userAgent)
                if (secret != null && hop == 0 && isFoyerFile(target.toString())) c.setRequestProperty("Authorization", "Bearer $secret")
                hop++
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location") ?: throw IOException("redirection sans adresse")
                    target = URL(target, next)
                    return@repeat
                }
                if (code != 200) throw IOException("réponse $code")
                val bytes = c.inputStream.use { readAll(it, 4 * 1024 * 1024, deadline) }
                if (!isImage(bytes)) throw IOException("pas une image JPEG, PNG ou WebP")
                return bytes
            } finally {
                c.disconnect()
            }
        }
        throw IOException("trop de redirections")
    }

    /**
     * Un fichier de l'écran de veille (GIF, vidéo MP4, image), écrit directement dans [dir] sous
     * « [stem].<extension> » sans passer par la mémoire : https seulement, jamais une adresse du
     * réseau local, redirections vérifiées une à une (Foyer renvoie vers un lien signé du
     * stockage), 15 Mo et 2 minutes au plus, et seulement un fichier du [kind] annoncé, reconnu à
     * ses premiers octets. La clé du robot ne part qu'avec la première requête, vers Foyer.
     */
    fun downloadMedia(url: String, kind: String, userAgent: String, secret: String?, dir: File, stem: String): File {
        val deadline = System.currentTimeMillis() + 120_000
        var target = URL(url)
        var hop = 0
        repeat(4) {
            if (target.protocol != "https") throw IOException("https uniquement")
            if (InetAddress.getAllByName(target.host).any { isLocal(it) }) throw IOException("adresse locale refusée")
            val c = open(target.toString())
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 10_000
                c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", userAgent)
                if (secret != null && hop == 0 && isFoyerFile(target.toString())) c.setRequestProperty("Authorization", "Bearer $secret")
                hop++
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location") ?: throw IOException("redirection sans adresse")
                    target = URL(target, next)
                    return@repeat
                }
                if (code != 200) throw IOException("réponse $code")
                val declared = c.contentLength
                if (declared > MEDIA_MAX_BYTES) throw IOException("fichier trop lourd")
                val tmp = File(dir, "$stem.part")
                try {
                    var size = 0L
                    val head = ByteArray(16)
                    var headSize = 0
                    c.inputStream.use { input ->
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                if (headSize < head.size) {
                                    val k = minOf(n, head.size - headSize)
                                    System.arraycopy(buf, 0, head, headSize, k)
                                    headSize += k
                                }
                                size += n
                                if (size > MEDIA_MAX_BYTES) throw IOException("fichier trop lourd")
                                if (System.currentTimeMillis() > deadline) throw IOException("trop lent")
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    val ext = MediaSniff.ext(head.copyOf(headSize))
                    if (!MediaSniff.fits(kind, ext)) throw IOException("pas un fichier $kind reconnu")
                    val dest = File(dir, "$stem.$ext")
                    if (!tmp.renameTo(dest)) throw IOException("enregistrement impossible")
                    return dest
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("trop de redirections")
    }

    const val MEDIA_MAX_BYTES = 15L * 1024 * 1024

    /** Boucle locale, réseau privé, lien local, CGNAT, adresses IPv6 uniques locales. */
    fun isLocal(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        if (a is Inet4Address) return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
        if (a is Inet6Address) return (b[0].toInt() and 0xFE) == 0xFC
        return false
    }

    fun isImage(b: ByteArray): Boolean {
        fun at(i: Int, vararg v: Int) = b.size > i + v.size && v.withIndex().all { (k, x) -> (b[i + k].toInt() and 0xFF) == x }
        return at(0, 0xFF, 0xD8, 0xFF) ||
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ||
            (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50))
    }

    private fun readAll(input: java.io.InputStream, max: Int, deadline: Long = Long.MAX_VALUE): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("réponse trop grande")
            if (System.currentTimeMillis() > deadline) throw IOException("trop lent")
        }
        return out.toByteArray()
    }
}
