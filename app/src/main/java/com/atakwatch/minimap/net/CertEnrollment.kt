package com.atakwatch.minimap.net

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TAK Server certificate enrollment — the same flow ATAK's "Quick Connect"
 * uses against the Marti certificate API:
 *
 *   1. `GET  /Marti/api/tls/config` (HTTP Basic) → X.500 name entries (O/OU)
 *   2. generate RSA-2048 keypair + PKCS#10 CSR (CN = username + O/OU)
 *   3. `POST /Marti/api/tls/signClient/v2?clientUid=<uid>&version=2`
 *      body = base64 PKCS#10 DER → JSON { signedCert, ca0, ca1, … }
 *   4. persist identity (PKCS12) + pin the returned CAs as the truststore
 *
 * TLS during enrollment is trust-on-first-use (the server's CA isn't known
 * yet — same as ATAK's quick connect); every later CoT connection is pinned
 * to the CAs returned here.
 */
object CertEnrollment {

    private const val TAG = "CertEnrollment"

    sealed class Status(val label: String) {
        data object Idle : Status("—")
        data object Running : Status("Enrolling…")
        class Done(cn: String?) : Status("Enrolled${cn?.let { " · $it" } ?: ""}")
        class Failed(msg: String) : Status("Failed: $msg")
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Server + credential config, read from tak_server.json (adb-pushed). */
    data class Config(
        val host: String,
        val port: Int,          // plain STCP
        val tlsPort: Int,       // TLS streaming CoT
        val enrollPort: Int,    // Marti cert API (https)
        val username: String,
        val password: String,
    )

    fun loadConfig(context: Context): Config? = runCatching {
        val f = File(context.getExternalFilesDir(null), "tak_server.json")
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        Config(
            host = o.getString("host"),
            port = o.optInt("port", 8087),
            tlsPort = o.optInt("tlsPort", 8089),
            enrollPort = o.optInt("enrollPort", 8446),
            username = o.optString("username", ""),
            password = o.optString("password", ""),
        )
    }.getOrNull()

    suspend fun enroll(context: Context): Boolean = withContext(Dispatchers.IO) {
        val cfg = loadConfig(context)
        if (cfg == null || cfg.username.isEmpty()) {
            _status.value = Status.Failed("no tak_server.json credentials")
            return@withContext false
        }
        _status.value = Status.Running
        try {
            val basic = "Basic " + Base64.encodeToString(
                "${cfg.username}:${cfg.password}".toByteArray(), Base64.NO_WRAP
            )
            val base = "https://${cfg.host}:${cfg.enrollPort}"

            // 1. TLS config → name entries for the CSR subject (tolerate absence).
            val nameEntries = runCatching { fetchConfig("$base/Marti/api/tls/config", basic) }
                .getOrElse { emptyMap() }

            // 2. Keypair + CSR.
            val keyPair = KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048, SecureRandom()) }.generateKeyPair()
            val nameBuilder = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, cfg.username)
            nameEntries["O"]?.let { nameBuilder.addRDN(BCStyle.O, it) }
            nameEntries["OU"]?.let { nameBuilder.addRDN(BCStyle.OU, it) }
            val csr = JcaPKCS10CertificationRequestBuilder(nameBuilder.build(), keyPair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
            val csrB64 = Base64.encodeToString(csr.encoded, Base64.NO_WRAP)

            // 3. Sign.
            val uid = DeviceIdentity.uid
            val url = "$base/Marti/api/tls/signClient/v2?clientUid=$uid&version=2"
            val json = postCsr(url, basic, csrB64)

            // 4. Parse + persist.
            val cf = CertificateFactory.getInstance("X.509")
            fun cert(b64: String): X509Certificate =
                cf.generateCertificate(ByteArrayInputStream(Base64.decode(b64, Base64.DEFAULT))) as X509Certificate

            val o = JSONObject(json)
            val signed = cert(o.getString("signedCert"))
            val cas = generateSequence(0) { it + 1 }
                .map { "ca$it" }
                .takeWhile { o.has(it) }
                .map { cert(o.getString(it)) }
                .toList()
            if (cas.isEmpty()) throw IllegalStateException("no CA certs in enrollment response")

            CertStore.store(context, keyPair.private, listOf(signed) + cas, cas)
            val cn = CertStore.identitySummary(context)
            Log.i(TAG, "enrolled: cert CN=$cn, ${cas.size} CA(s) pinned")
            _status.value = Status.Done(cn)
            true
        } catch (e: Exception) {
            Log.w(TAG, "enrollment failed", e)
            _status.value = Status.Failed(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    // ---- HTTP helpers (trust-on-first-use TLS for the enrollment calls) ----

    private fun trustAllConnection(url: String): HttpsURLConnection {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
        return (URL(url).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = ctx.socketFactory
            setHostnameVerifier { _, _ -> true }
            connectTimeout = 8_000
            readTimeout = 10_000
        }
    }

    private fun fetchConfig(url: String, basic: String): Map<String, String> {
        val conn = trustAllConnection(url).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", basic)
            setRequestProperty("Accept", "application/xml")
        }
        try {
            check(conn.responseCode == HttpURLConnection.HTTP_OK) { "config HTTP ${conn.responseCode}" }
            val xml = conn.inputStream.bufferedReader().readText()
            // <nameEntry name="O" value="…"/> pairs
            return Regex("nameEntry\\s+name=\"([^\"]+)\"\\s+value=\"([^\"]+)\"")
                .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        } finally {
            conn.disconnect()
        }
    }

    private fun postCsr(url: String, basic: String, csrB64: String): String {
        val conn = trustAllConnection(url).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", basic)
            setRequestProperty("Content-Type", "application/pkcs10")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(csrB64.toByteArray()) }
            check(conn.responseCode == HttpURLConnection.HTTP_OK) { "signClient HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
