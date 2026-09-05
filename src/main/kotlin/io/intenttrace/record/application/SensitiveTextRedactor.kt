package io.intenttrace.record.application

import org.springframework.stereotype.Component

@Component
class SensitiveTextRedactor {
    private val assignmentPattern = Regex(
        pattern = "(?i)([\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|secret|private[_-]?key|token)[\"']?\\s*[:=]\\s*)(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;]+)",
    )
    private val privateKeyPattern = Regex(
        pattern = "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
        option = RegexOption.DOT_MATCHES_ALL,
    )
    private val bearerPattern = Regex("(?i)\\bBearer\\s+[\"']?[A-Za-z0-9._~+/=-]+[\"']?")
    private val knownTokenPattern = Regex("(?i)\\b(?:ghp|gho|ghu|ghs|ghr|github_pat|its|itb)_[A-Za-z0-9_=-]+\\b")
    private val unixHomePathPattern = Regex("(?i)/(?:Users|home)/[^\\s\"'`,;)\\]}]+")
    private val windowsHomePathPattern = Regex("(?i)[A-Z]:\\\\Users\\\\[^\\s\"'`,;)\\]}]+")

    fun redact(value: String): String = value
        .replace(privateKeyPattern, "[REDACTED]")
        .replace(assignmentPattern) { match -> "${match.groupValues[1]}[REDACTED]" }
        .replace(bearerPattern, "Bearer [REDACTED]")
        .replace(knownTokenPattern, "[REDACTED]")
        .replace(unixHomePathPattern, "[REDACTED]")
        .replace(windowsHomePathPattern, "[REDACTED]")

    fun redactNullable(value: String?): String? = value?.let(::redact)
}
