package io.intenttrace

import io.intenttrace.identity.adapter.`in`.web.GitHubOAuthController
import jakarta.servlet.http.Cookie
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get

/** GitHub 로그인을 시작하고 state 쿠키를 돌려준다. 쿠키 값이 승인 주소의 state와 같다. */
fun MockMvc.startGitHubLogin(returnTo: String? = null): Cookie = get("/auth/github/start") {
    returnTo?.let { param("returnTo", it) }
}.andExpect { status { isFound() } }.andReturn().response.cookies.single { it.name == GitHubOAuthController.STATE_COOKIE }

/** [state]를 바꾸면 쿠키와 다른 state로 callback을 호출한다. MockMvc의 param은 값을 덧붙이므로 [dsl]로 바꾸지 않는다. */
fun MockMvc.githubCallback(stateCookie: Cookie, state: String = stateCookie.value, code: String? = "authorization-code",
    dsl: MockHttpServletRequestDsl.() -> Unit = {}): ResultActionsDsl = get("/auth/github/callback") {
    cookie(stateCookie)
    param("state", state)
    code?.let { param("code", it) }
    dsl()
}
