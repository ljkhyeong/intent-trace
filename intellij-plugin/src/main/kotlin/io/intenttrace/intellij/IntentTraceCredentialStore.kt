package io.intenttrace.intellij

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.CredentialStore
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

internal class IntentTraceCredentialStore(
    private val credentialStore: CredentialStore = PasswordSafe.instance,
    private val environmentToken: () -> String? = { System.getenv(IntentTraceApiClient.TOKEN_ENV) },
) {
    fun load(server: IntentTraceServer): String? {
        val stored = credentialStore.getPassword(attributes(server))
            ?.takeIf(IntentTraceApiClient::validSessionToken)
        return stored ?: environmentToken()
            ?.trim()
            ?.takeIf(IntentTraceApiClient::validSessionToken)
    }

    fun save(server: IntentTraceServer, sessionToken: String) {
        if (!IntentTraceApiClient.validSessionToken(sessionToken)) {
            throw IntentTraceUsageException("IntentTrace session token은 its_ 형식이어야 합니다.")
        }
        credentialStore.set(attributes(server), Credentials(null, sessionToken))
    }

    fun clear(server: IntentTraceServer) {
        credentialStore.set(attributes(server), null)
    }

    fun environmentSessionConfigured(): Boolean = environmentToken()
        ?.trim()
        ?.let(IntentTraceApiClient::validSessionToken)
        ?: false

    private fun attributes(server: IntentTraceServer): CredentialAttributes = CredentialAttributes(
        generateServiceName("IntentTrace", server.baseUri.toString()),
    )
}
