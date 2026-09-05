package io.intenttrace.publication.adapter.out.github

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import io.intenttrace.config.GitHubProperties
import io.intenttrace.publication.application.GitHubCredentialConfigurationException
import io.intenttrace.publication.application.GitHubCredentialMissingException
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Base64

fun interface GitHubAppJwtProvider {
    fun create(): String
}

@Component
class GitHubAppJwtFactory(
    private val properties: GitHubProperties,
    private val clock: Clock,
) : GitHubAppJwtProvider {
    override fun create(): String {
        val clientId = properties.app.clientId.trim()
        val privateKeyBase64 = properties.app.privateKeyBase64.trim()
        if (clientId.isEmpty() && privateKeyBase64.isEmpty()) {
            throw GitHubCredentialMissingException()
        }
        if (!CLIENT_ID.matches(clientId) || privateKeyBase64.isEmpty()) {
            throw GitHubCredentialConfigurationException()
        }

        return try {
            val now = Instant.now(clock)
            val privateKey = readPrivateKey(privateKeyBase64)
            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)) as RSAPublicKey
            // 키 식별자를 생성하지 않아 기존 App JWT 헤더를 유지한다.
            val jwk = RSAKey.Builder(publicKey).privateKey(privateKey).build()
            val encoder = NimbusJwtEncoder(ImmutableJWKSet(JWKSet(jwk)))
            val header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build()
            val claims = JwtClaimsSet.builder()
                .issuer(clientId)
                .issuedAt(now.minusSeconds(60))
                .expiresAt(now.plusSeconds(540))
                .build()
            encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        } catch (_: Exception) {
            throw GitHubCredentialConfigurationException()
        }
    }

    private fun readPrivateKey(encodedPem: String): RSAPrivateCrtKey {
        val pem = String(Base64.getDecoder().decode(encodedPem), StandardCharsets.US_ASCII).trim()
        val keyBytes = when {
            pem.contains(BEGIN_PRIVATE_KEY) -> decodePem(pem, BEGIN_PRIVATE_KEY, END_PRIVATE_KEY)
            pem.contains(BEGIN_RSA_PRIVATE_KEY) -> wrapPkcs1AsPkcs8(
                decodePem(pem, BEGIN_RSA_PRIVATE_KEY, END_RSA_PRIVATE_KEY),
            )
            else -> throw IllegalArgumentException("지원하지 않는 private key 형식")
        }
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes)) as RSAPrivateCrtKey
    }

    private fun decodePem(pem: String, begin: String, end: String): ByteArray {
        val body = pem.substringAfter(begin).substringBefore(end).replace(WHITESPACE, "")
        require(body.isNotEmpty())
        return Base64.getDecoder().decode(body)
    }

    private fun wrapPkcs1AsPkcs8(pkcs1: ByteArray): ByteArray = der(
        tag = 0x30,
        content = byteArrayOf(0x02, 0x01, 0x00) + RSA_ALGORITHM_IDENTIFIER + der(0x04, pkcs1),
    )

    private fun der(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLength(content.size) + content

    private fun derLength(length: Int): ByteArray {
        if (length < 128) {
            return byteArrayOf(length.toByte())
        }
        var remaining = length
        val bytes = mutableListOf<Byte>()
        while (remaining > 0) {
            bytes.add(0, (remaining and 0xff).toByte())
            remaining = remaining ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    companion object {
        private const val BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----"
        private const val END_PRIVATE_KEY = "-----END PRIVATE KEY-----"
        private const val BEGIN_RSA_PRIVATE_KEY = "-----BEGIN RSA PRIVATE KEY-----"
        private const val END_RSA_PRIVATE_KEY = "-----END RSA PRIVATE KEY-----"
        private val CLIENT_ID = Regex("^[A-Za-z0-9_.-]{1,100}$")
        private val WHITESPACE = Regex("\\s")
        private val RSA_ALGORITHM_IDENTIFIER = byteArrayOf(
            0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(),
            0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
        )
    }
}
