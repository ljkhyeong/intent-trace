package io.intenttrace.intellij

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.CredentialStore
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

internal class IntentTraceCredentialStore(
    private val credentialStore: CredentialStore = PasswordSafe.instance,
    private val environmentUrl: () -> String? = { System.getenv(IntentTraceServer.URL_ENV) },
    private val environmentToken: () -> String? = { System.getenv(IntentTraceApiClient.TOKEN_ENV) },
) {
    fun load(server: IntentTraceServer): String? {
        return loadStored(server) ?: environmentSession(server)
    }

    fun loadStored(server: IntentTraceServer): String? = credentialStore.getPassword(attributes(server))
        ?.takeIf(IntentTraceApiClient::validSessionToken)

    fun save(server: IntentTraceServer, sessionToken: String) {
        if (!IntentTraceApiClient.validSessionToken(sessionToken)) {
            throw IntentTraceUsageException("IntentTrace session token은 its_ 형식이어야 합니다.")
        }
        credentialStore.set(attributes(server), Credentials(null, sessionToken))
    }

    fun clear(server: IntentTraceServer) {
        credentialStore.set(attributes(server), null)
    }

    fun environmentSessionConfigured(server: IntentTraceServer): Boolean = environmentSession(server) != null

    private fun environmentSession(server: IntentTraceServer): String? {
        val environmentServer = try {
            IntentTraceServer.parse(environmentUrl())
        } catch (_: IntentTraceUsageException) {
            return null
        }
        // 설정에서 서버를 바꿔도 환경 변수의 세션을 새 서버로 전달하지 않는다.
        if (server.baseUri != environmentServer.baseUri) return null
        return environmentToken()?.trim()?.takeIf(IntentTraceApiClient::validSessionToken)
    }

    private fun attributes(server: IntentTraceServer): CredentialAttributes = CredentialAttributes(
        generateServiceName("IntentTrace", server.baseUri.toString()),
    )
}
