package com.atakwatch.minimap.net

import android.content.Context
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Persistence for the watch's TAK client identity: the enrolled private key +
 * signed certificate chain (PKCS12) and the server CA certificates from
 * enrollment (the pinned truststore). Everything lives in app-private storage
 * (`filesDir`), keystore password is random and stored alongside — standard for
 * an on-device client keystore.
 */
object CertStore {

    private const val CLIENT_KS = "tak_client.p12"
    private const val TRUST_KS = "tak_trust.p12"
    private const val PW_FILE = "tak_ks.pw"

    fun hasIdentity(context: Context): Boolean =
        File(context.filesDir, CLIENT_KS).exists() && File(context.filesDir, TRUST_KS).exists()

    fun clear(context: Context) {
        File(context.filesDir, CLIENT_KS).delete()
        File(context.filesDir, TRUST_KS).delete()
        File(context.filesDir, PW_FILE).delete()
    }

    private fun password(context: Context): CharArray {
        val f = File(context.filesDir, PW_FILE)
        if (!f.exists()) {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            f.writeText(bytes.joinToString("") { "%02x".format(it) })
        }
        return f.readText().trim().toCharArray()
    }

    /** Store the enrolled identity + CA chain. */
    fun store(context: Context, key: PrivateKey, chain: List<X509Certificate>, cas: List<X509Certificate>) {
        val pw = password(context)

        val client = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        client.setKeyEntry("client", key, pw, chain.toTypedArray())
        File(context.filesDir, CLIENT_KS).outputStream().use { client.store(it, pw) }

        val trust = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        cas.forEachIndexed { i, ca -> trust.setCertificateEntry("ca$i", ca) }
        File(context.filesDir, TRUST_KS).outputStream().use { trust.store(it, pw) }
    }

    /** Human-readable summary of the enrolled cert, or null. */
    fun identitySummary(context: Context): String? = runCatching {
        if (!hasIdentity(context)) return null
        val pw = password(context)
        val ks = KeyStore.getInstance("PKCS12").apply {
            File(context.filesDir, CLIENT_KS).inputStream().use { load(it, pw) }
        }
        val cert = ks.getCertificate("client") as? X509Certificate ?: return null
        val cn = cert.subjectX500Principal.name
            .split(',').firstOrNull { it.trim().startsWith("CN=") }?.trim()?.removePrefix("CN=")
        "$cn"
    }.getOrNull()

    /**
     * Mutual-TLS context for the streaming CoT connection: our enrolled client
     * identity, trusting exactly the CAs the server handed us at enrollment
     * (certificate pinning, the way ATAK treats an enrolled trust store).
     */
    fun sslContext(context: Context): SSLContext {
        val pw = password(context)
        val client = KeyStore.getInstance("PKCS12").apply {
            File(context.filesDir, CLIENT_KS).inputStream().use { load(it, pw) }
        }
        val trust = KeyStore.getInstance("PKCS12").apply {
            File(context.filesDir, TRUST_KS).inputStream().use { load(it, pw) }
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(client, pw) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(trust) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tmf.trustManagers, null) }
    }
}
