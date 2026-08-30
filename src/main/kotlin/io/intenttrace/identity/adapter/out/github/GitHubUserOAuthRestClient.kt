package io.intenttrace.identity.adapter.out.github

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.intenttrace.config.GitHubProperties
import io.intenttrace.identity.application.GitHubOAuthApiException
import io.intenttrace.identity.application.GitHubOAuthConfigurationException
import io.intenttrace.identity.application.GitHubOAuthRefreshRejectedException
import io.intenttrace.identity.application.GitHubUserOAuthGateway
import io.intenttrace.identity.application.GitHubUserOAuthTokens
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant

@Component
class GitHubUserOAuthRestClient(
    restClientBuilder: RestClient.Builder,
    private val properties: GitHubProperties,
    private val clock: Clock,
) : GitHubUserOAuthGateway {
    private val client = restClientBuilder
        .baseUrl(properties.userAuthorization.webBaseUrl.toString().trimEnd('/'))
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .build()

    override fun authorizationUri(state: String, codeChallenge: String): URI {
        requireConfiguration()
        require(PKCE_CHALLENGE.matches(codeChallenge)) { "PKCE code challenge 형식이 올바르지 않습니다." }
        return UriComponentsBuilder.fromUri(properties.userAuthorization.webBaseUrl)
            .path("/login/oauth/authorize")
            .queryParam("client_id", properties.app.clientId)
            .queryParam("redirect_uri", properties.userAuthorization.callbackUrl)
            .queryParam("state", state)
            .queryParam("code_challenge", codeChallenge)
            .queryParam("code_challenge_method", "S256")
            .queryParam("allow_signup", false)
            .queryParam("prompt", "select_account")
            .build()
            .encode()
            .toUri()
    }

    override fun exchange(code: String, codeVerifier: String): GitHubUserOAuthTokens {
        require(PKCE_VERIFIER.matches(codeVerifier)) { "PKCE code verifier 형식이 올바르지 않습니다." }
        return tokenRequest(
            operation = "사용자 승인 code 교환",
            form = form(
                "client_id" to properties.app.clientId,
                "client_secret" to properties.userAuthorization.clientSecret,
                "code" to code,
                "redirect_uri" to properties.userAuthorization.callbackUrl.toString(),
                "code_verifier" to codeVerifier,
            ),
            refresh = false,
        )
    }

    override fun refresh(refreshToken: String): GitHubUserOAuthTokens = tokenRequest(
        operation = "사용자 token 갱신",
        form = form(
            "client_id" to properties.app.clientId,
            "client_secret" to properties.userAuthorization.clientSecret,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        ),
        refresh = true,
    )

    private fun tokenRequest(
        operation: String,
        form: LinkedMultiValueMap<String, String>,
        refresh: Boolean,
    ): GitHubUserOAuthTokens {
        requireConfiguration()
        try {
            val response = client.post()
                .uri("/login/oauth/access_token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(GitHubOAuthTokenResponse::class.java)
                ?: throw GitHubOAuthApiException("GitHub $operation 응답이 비어 있습니다.")
            response.error?.let { error ->
                if (error == "incorrect_client_credentials") throw GitHubOAuthConfigurationException()
                if (refresh && error == "bad_refresh_token") throw GitHubOAuthRefreshRejectedException()
                throw GitHubOAuthApiException("GitHub $operation 요청이 거부됐습니다.")
            }
            return response.toTokens()
        } catch (exception: RestClientResponseException) {
            throw GitHubOAuthApiException("GitHub $operation 요청이 실패했습니다. HTTP ${exception.statusCode.value()}")
        } catch (_: RestClientException) {
            throw GitHubOAuthApiException("GitHub $operation 요청을 완료하지 못했습니다.")
        }
    }

    private fun GitHubOAuthTokenResponse.toTokens(): GitHubUserOAuthTokens {
        val access = accessToken ?: throw GitHubOAuthConfigurationException()
        val accessLifetime = expiresIn?.takeIf { it > 0 } ?: throw GitHubOAuthConfigurationException()
        val refresh = refreshToken ?: throw GitHubOAuthConfigurationException()
        val refreshLifetime = refreshTokenExpiresIn?.takeIf { it > 0 } ?: throw GitHubOAuthConfigurationException()
        if (!tokenType.equals("bearer", ignoreCase = true)) throw GitHubOAuthConfigurationException()
        val now = Instant.now(clock)
        return try {
            GitHubUserOAuthTokens(
                accessToken = access,
                accessExpiresAt = now.plusSeconds(accessLifetime),
                refreshToken = refresh,
                refreshExpiresAt = now.plusSeconds(refreshLifetime),
            )
        } catch (_: IllegalArgumentException) {
            throw GitHubOAuthApiException("GitHub token 응답 값이 올바르지 않습니다.")
        } catch (_: DateTimeException) {
            throw GitHubOAuthApiException("GitHub token 응답의 만료 시각을 처리할 수 없습니다.")
        } catch (_: ArithmeticException) {
            throw GitHubOAuthApiException("GitHub token 응답의 만료 시각을 처리할 수 없습니다.")
        }
    }

    private fun requireConfiguration() {
        if (properties.app.clientId.isBlank() || properties.userAuthorization.clientSecret.isBlank()) {
            throw GitHubOAuthConfigurationException()
        }
    }

    private fun form(vararg entries: Pair<String, String>): LinkedMultiValueMap<String, String> =
        LinkedMultiValueMap<String, String>().also { form -> entries.forEach { form.add(it.first, it.second) } }

    companion object {
        private val PKCE_CHALLENGE = Regex("^[A-Za-z0-9_-]{43}$")
        private val PKCE_VERIFIER = Regex("^[A-Za-z0-9._~-]{43,128}$")
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class GitHubOAuthTokenResponse(
    @JsonProperty("access_token") val accessToken: String? = null,
    @JsonProperty("expires_in") val expiresIn: Long? = null,
    @JsonProperty("refresh_token") val refreshToken: String? = null,
    @JsonProperty("refresh_token_expires_in") val refreshTokenExpiresIn: Long? = null,
    @JsonProperty("token_type") val tokenType: String? = null,
    val error: String? = null,
) {
    override fun toString(): String =
        "GitHubOAuthTokenResponse(accessToken=[보호됨], expiresIn=$expiresIn, " +
            "refreshToken=[보호됨], refreshTokenExpiresIn=$refreshTokenExpiresIn, tokenType=$tokenType, error=$error)"
}
