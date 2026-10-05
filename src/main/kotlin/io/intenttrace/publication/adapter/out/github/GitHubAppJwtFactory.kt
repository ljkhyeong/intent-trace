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
import org.springframework.boot.ssl.pem.PemContent
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
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

    // PKCS#1·PKCS#8 PEM 해석은 Spring Boot의 PEM 파서에 맡긴다. RSA가 아닌 키는 설정 오류로 처리한다.
    private fun readPrivateKey(encodedPem: String): RSAPrivateCrtKey =
        requireNotNull(PemContent.of(String(Base64.getDecoder().decode(encodedPem), StandardCharsets.US_ASCII))).privateKey as RSAPrivateCrtKey

    companion object {
        private val CLIENT_ID = Regex("^[A-Za-z0-9_.-]{1,100}$")
    }
}
