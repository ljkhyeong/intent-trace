package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.GitHubAuthorizationWebhookService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
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
) {
    @PostMapping("/webhooks/github", consumes = ["application/json"])
    fun receive(request: HttpServletRequest): ResponseEntity<Void> {
        if (properties.webhookSecret.isBlank()) return ResponseEntity.status(503).build()
        val payload = request.inputStream.readNBytes(MAX_PAYLOAD_BYTES + 1)
        if (payload.size > MAX_PAYLOAD_BYTES) return ResponseEntity.status(413).build()
        if (!GitHubWebhookSignature.matches(properties.webhookSecret, payload, request.getHeader("X-Hub-Signature-256"))) {
            return ResponseEntity.status(401).build()
        }
        if (request.getHeader("X-GitHub-Event") != "github_app_authorization") return ResponseEntity.noContent().build()
        val event = try {
            mapper.readTree(payload)
        } catch (_: JacksonException) {
            return ResponseEntity.badRequest().build()
        }
        if (!event.isObject || event["action"] == null) return ResponseEntity.badRequest().build()
        if (event["action"]?.asString() != "revoked") return ResponseEntity.noContent().build()
        val id = event["sender"]?.get("id")
        if (id == null || !id.isIntegralNumber || !id.canConvertToLong() || id.longValue() <= 0) {
            return ResponseEntity.badRequest().build()
        }
        authorization.revoked(id.longValue())
        return ResponseEntity.noContent().build()
    }

    companion object {
        private const val MAX_PAYLOAD_BYTES = 1_048_576
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
