package io.intenttrace.publication.adapter.out.github

import io.intenttrace.config.GitHubProperties
import io.intenttrace.publication.application.GitHubCredentialConfigurationException
import io.intenttrace.publication.application.GitHubCredentialMissingException
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
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
            val header = encodeUrl("""{"alg":"RS256","typ":"JWT"}""".toByteArray(StandardCharsets.UTF_8))
            val payload = encodeUrl(
                """{"iat":${now.minusSeconds(60).epochSecond},"exp":${now.plusSeconds(540).epochSecond},"iss":"$clientId"}"""
                    .toByteArray(StandardCharsets.UTF_8),
            )
            val signingInput = "$header.$payload"
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(readPrivateKey(privateKeyBase64))
                update(signingInput.toByteArray(StandardCharsets.US_ASCII))
                sign()
            }
            "$signingInput.${encodeUrl(signature)}"
        } catch (_: Exception) {
            throw GitHubCredentialConfigurationException()
        }
    }

    private fun readPrivateKey(encodedPem: String): PrivateKey {
        val pem = String(Base64.getDecoder().decode(encodedPem), StandardCharsets.US_ASCII).trim()
        val keyBytes = when {
            pem.contains(BEGIN_PRIVATE_KEY) -> decodePem(pem, BEGIN_PRIVATE_KEY, END_PRIVATE_KEY)
            pem.contains(BEGIN_RSA_PRIVATE_KEY) -> wrapPkcs1AsPkcs8(
                decodePem(pem, BEGIN_RSA_PRIVATE_KEY, END_RSA_PRIVATE_KEY),
            )
            else -> throw IllegalArgumentException("지원하지 않는 private key 형식")
        }
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
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

    private fun encodeUrl(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

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
