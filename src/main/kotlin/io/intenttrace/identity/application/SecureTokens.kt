package io.intenttrace.identity.application

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

internal object SecureTokens {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun random(): String = ByteArray(32).also(random::nextBytes).let(encoder::encodeToString)
}

internal object TokenDigests {
    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.US_ASCII))
        .let(HexFormat.of()::formatHex)
}
