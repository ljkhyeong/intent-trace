package io.intenttrace.record.application

import org.springframework.stereotype.Component

@Component
class SensitiveTextRedactor {
    private val assignmentPattern = Regex(
        pattern = "(?i)\\b(api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)\\b(\\s*[:=]\\s*)([^\\s,;]+)",
    )

    fun redact(value: String): String = assignmentPattern.replace(value) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
    }

    fun redactNullable(value: String?): String? = value?.let(::redact)
}
