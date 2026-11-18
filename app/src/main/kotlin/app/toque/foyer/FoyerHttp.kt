package app.toque.foyer

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
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
 * les connexions de Toque vers Foyer et vers les photos des recettes font confiance aux
 * racines du système plus trois racines embarquées (assets/certs/foyer-roots.pem). Le nom
 * du serveur reste vérifié normalement.
 */
object FoyerHttp {
    const val BASE = "https://foyer-life.netlify.app"

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

    /** Une photo de recette (https, 8 Mo au plus). */
    fun download(url: String, userAgent: String): ByteArray {
        val c = open(url)
        try {
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", userAgent)
            if (c.responseCode != 200) throw IOException("réponse ${c.responseCode}")
            return c.inputStream.use { readAll(it, 8 * 1024 * 1024) }
        } finally {
            c.disconnect()
        }
    }

    private fun readAll(input: java.io.InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("réponse trop grande")
        }
        return out.toByteArray()
    }
}
