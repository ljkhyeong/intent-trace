package io.intenttrace.intellij

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

internal class IntentTraceCredentialStore {
    fun load(server: IntentTraceServer): String? {
        val stored = PasswordSafe.instance.getPassword(attributes(server))
            ?.takeIf(IntentTraceApiClient::validSessionToken)
        return stored ?: System.getenv(IntentTraceApiClient.TOKEN_ENV)
            ?.trim()
            ?.takeIf(IntentTraceApiClient::validSessionToken)
    }

    fun save(server: IntentTraceServer, sessionToken: String) {
        if (!IntentTraceApiClient.validSessionToken(sessionToken)) {
            throw IntentTraceUsageException("IntentTrace session token은 its_ 형식이어야 합니다.")
        }
        PasswordSafe.instance.set(attributes(server), Credentials(null, sessionToken))
    }

    private fun attributes(server: IntentTraceServer): CredentialAttributes = CredentialAttributes(
        generateServiceName("IntentTrace", server.baseUri.toString()),
    )
}
