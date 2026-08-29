package io.intenttrace.publication.adapter.out.github

import io.intenttrace.config.GitHubAppProperties
import io.intenttrace.config.GitHubProperties
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class GitHubAppJwtFactoryTest {
    private val objectMapper = ObjectMapper()

    @Test
    fun `PKCS1 RSA key로 GitHub App 규칙에 맞는 JWT를 만든다`() {
        val now = Instant.parse("2026-08-28T00:00:00Z")
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = """
            -----BEGIN RSA PRIVATE KEY-----
            ${Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(pkcs1(keyPair.private as RSAPrivateCrtKey))}
            -----END RSA PRIVATE KEY-----
        """.trimIndent()
        val factory = GitHubAppJwtFactory(
            properties = GitHubProperties(
                app = GitHubAppProperties(
                    clientId = "Iv1.intent-trace",
                    privateKeyBase64 = Base64.getEncoder().encodeToString(pem.toByteArray(StandardCharsets.US_ASCII)),
                ),
            ),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        val jwt = factory.create()
        val parts = jwt.split('.')

        assertEquals(3, parts.size)
        assertEquals(
            objectMapper.readTree("""{"alg":"RS256","typ":"JWT"}"""),
            decodeJson(parts[0]),
        )
        assertEquals(
            objectMapper.readTree("""{"iat":1787875140,"exp":1787875740,"iss":"Iv1.intent-trace"}"""),
            decodeJson(parts[1]),
        )
        assertTrue(
            Signature.getInstance("SHA256withRSA").run {
                initVerify(keyPair.public)
                update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
                verify(Base64.getUrlDecoder().decode(parts[2]))
            },
        )
    }

    private fun decodeJson(value: String): JsonNode =
        objectMapper.readTree(Base64.getUrlDecoder().decode(value))

    private fun pkcs1(key: RSAPrivateCrtKey): ByteArray = der(
        0x30,
        listOf(
            java.math.BigInteger.ZERO,
            key.modulus,
            key.publicExponent,
            key.privateExponent,
            key.primeP,
            key.primeQ,
            key.primeExponentP,
            key.primeExponentQ,
            key.crtCoefficient,
        ).fold(byteArrayOf()) { encoded, value -> encoded + der(0x02, value.toByteArray()) },
    )

    private fun der(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLength(content.size) + content

    private fun derLength(length: Int): ByteArray {
        if (length < 128) return byteArrayOf(length.toByte())
        val bytes = generateSequence(length) { it ushr 8 }
            .takeWhile { it > 0 }
            .map { (it and 0xff).toByte() }
            .toList()
            .reversed()
            .toByteArray()
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes
    }
}
