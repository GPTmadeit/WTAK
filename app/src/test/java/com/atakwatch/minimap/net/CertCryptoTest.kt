package com.atakwatch.minimap.net

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.KeyStore

/**
 * Pure-JVM tests of the certificate crypto the enrollment flow relies on: a
 * BouncyCastle PKCS#10 CSR must be well-formed, self-consistent (its signature
 * verifies against its own public key), and carry the right subject — and a
 * PKCS12 keystore round-trips a key + chain. These prove the crypto path off
 * the device; the live Marti enrollment + mTLS is verified on the emulator.
 */
class CertCryptoTest {

    @Test
    fun `generated PKCS10 CSR is well-formed and self-verifying`() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject: X500Name = X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.CN, "watchuser")
            .addRDN(BCStyle.O, "FakeTAK")
            .addRDN(BCStyle.OU, "Watch")
            .build()
        val csr = JcaPKCS10CertificationRequestBuilder(subject, kp.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))

        // Re-parse the DER, like the server would.
        val parsed = JcaPKCS10CertificationRequest(csr.encoded)
        assertEquals(subject, parsed.subject)
        // Signature must verify against the embedded public key (proves a valid CSR).
        val verifier = org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder()
            .build(parsed.publicKey)
        assertTrue("CSR signature must verify", parsed.isSignatureValid(verifier))
        assertEquals("RSA", parsed.publicKey.algorithm)
    }

    @Test
    fun `PKCS12 keystore round-trips a key and cert chain`() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = SelfSigned.make(kp)
        val pw = "test-pw".toCharArray()

        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("client", kp.private, pw, arrayOf(cert))
        val bytes = java.io.ByteArrayOutputStream().apply { ks.store(this, pw) }.toByteArray()

        val reloaded = KeyStore.getInstance("PKCS12").apply {
            load(java.io.ByteArrayInputStream(bytes), pw)
        }
        assertTrue(reloaded.isKeyEntry("client"))
        assertEquals(kp.public, reloaded.getCertificate("client").publicKey)
    }
}

/** Minimal self-signed X.509 via BouncyCastle, for the keystore test. */
private object SelfSigned {
    fun make(kp: java.security.KeyPair): java.security.cert.X509Certificate {
        val now = System.currentTimeMillis()
        val name = org.bouncycastle.asn1.x500.X500Name("CN=test")
        val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            name, java.math.BigInteger.valueOf(now),
            java.util.Date(now - 1000), java.util.Date(now + 86_400_000),
            name, kp.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .getCertificate(builder.build(signer))
    }
}
