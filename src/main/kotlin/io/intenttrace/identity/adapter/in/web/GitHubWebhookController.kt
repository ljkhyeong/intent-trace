package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.GitHubAuthorizationWebhookService
import io.intenttrace.publication.application.GitHubInstallationWebhookService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RestController
class GitHubWebhookController(
    private val properties: GitHubProperties,
    private val mapper: ObjectMapper,
    private val authorization: GitHubAuthorizationWebhookService,
    private val installations: GitHubInstallationWebhookService,
) {
    @PostMapping("/webhooks/github", consumes = ["application/json"])
    fun receive(request: HttpServletRequest): ResponseEntity<Void> {
        if (properties.webhookSecret.isBlank()) return ResponseEntity.status(503).build()
        val payload = request.inputStream.readNBytes(MAX_PAYLOAD_BYTES + 1)
        if (payload.size > MAX_PAYLOAD_BYTES) return ResponseEntity.status(413).build()
        if (!GitHubWebhookSignature.matches(properties.webhookSecret, payload, request.getHeader("X-Hub-Signature-256"))) {
            return ResponseEntity.status(401).build()
        }
        val type = request.getHeader("X-GitHub-Event")
        if (type != AUTHORIZATION_EVENT && type !in INSTALLATION_EVENTS) return ResponseEntity.noContent().build()
        val event = try {
            mapper.readTree(payload)
        } catch (_: JacksonException) {
            return ResponseEntity.badRequest().build()
        }
        if (!event.isObject || event["action"] == null) return ResponseEntity.badRequest().build()
        if (type == AUTHORIZATION_EVENT) {
            if (event["action"]?.asString() != "revoked") return ResponseEntity.noContent().build()
            authorization.revoked(positiveId(event["sender"]) ?: return ResponseEntity.badRequest().build())
        } else {
            // 설치 이벤트의 모든 동작은 이전 권한·저장소 범위의 토큰을 버린다. 캐시가 비어 있으면 아무것도 바꾸지 않는다.
            installations.installationChanged(positiveId(event["installation"]) ?: return ResponseEntity.badRequest().build())
        }
        return ResponseEntity.noContent().build()
    }

    private fun positiveId(owner: JsonNode?): Long? = owner?.get("id")
        ?.takeIf { it.isIntegralNumber && it.canConvertToLong() && it.longValue() > 0 }?.longValue()

    companion object {
        private const val MAX_PAYLOAD_BYTES = 1_048_576
        private const val AUTHORIZATION_EVENT = "github_app_authorization"
        private val INSTALLATION_EVENTS = setOf("installation", "installation_repositories")
    }
}

internal object GitHubWebhookSignature {
    fun matches(secret: String, payload: ByteArray, signature: String?): Boolean {
        if (signature == null || signature.length != 71 || !signature.startsWith("sha256=")) return false
        val received = try {
            HexFormat.of().parseHex(signature.substring(7))
        } catch (_: IllegalArgumentException) {
            return false
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return MessageDigest.isEqual(mac.doFinal(payload), received)
    }
}
