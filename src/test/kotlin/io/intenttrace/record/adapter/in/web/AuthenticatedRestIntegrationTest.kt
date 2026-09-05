package io.intenttrace.record.adapter.`in`.web

import io.intenttrace.IntentTraceApplication
import io.intenttrace.identity.application.GitHubIdentityApiException
import io.intenttrace.identity.application.GitHubUserAccessGateway
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import io.intenttrace.identity.domain.RepositoryRole
import io.intenttrace.record.domain.PurposeSource
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [IntentTraceApplication::class, AuthenticatedRestIntegrationTest.RestTestConfiguration::class],
    properties = [
        "spring.datasource.url=jdbc:h2:mem:authenticated-rest-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.h2.console.enabled=false",
    ],
)
@AutoConfigureMockMvc
class AuthenticatedRestIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val userAccess: TestGitHubUserAccessGateway,
    @Autowired private val objectMapper: ObjectMapper,
) {
    @Test
    fun `REST 기록함은 본인 초안만 반환하고 권한 없는 저장소 조회를 거부한다`() {
        val repository = "acme/rest-history-${UUID.randomUUID()}"
        val created = mockMvc.post("/api/v1/change-records") {
            authorized()
            contentType = MediaType.APPLICATION_JSON
            content = createRequest(UUID.randomUUID().toString(), "목록 권한을 확인한다.")
                .replace("acme/intent-trace", repository)
        }.andExpect { status { isCreated() } }.andReturn()
        val id = objectMapper.readTree(created.response.contentAsString).get("id").stringValue()
        mockMvc.get("/api/v1/change-records?repositoryKey=$repository&scope=MY_DRAFTS&size=1") {
            authorized()
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].id") { value(id) }
            jsonPath("$.hasNext") { value(false) }
        }
        mockMvc.get("/api/v1/change-records?repositoryKey=$repository&scope=MINE&limit=1&path=./src/App.kt") {
            authorized()
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].id") { value(id) }
            jsonPath("$.size") { value(1) }
            jsonPath("$.hasNext") { value(false) }
        }
        mockMvc.get("/api/v1/change-records?repositoryKey=$repository") {
            authorized()
        }.andExpect { jsonPath("$.items") { isEmpty() } }
        userAccess.actor = ActorIdentity.github(84, "teammate")
        try {
            mockMvc.get("/api/v1/change-records?repositoryKey=$repository&scope=MY_DRAFTS") {
                authorized()
            }.andExpect {
                status { isOk() }
                jsonPath("$.items") { isEmpty() }
                jsonPath("$.hasNext") { value(false) }
            }
            userAccess.role = null
            mockMvc.get("/api/v1/change-records?repositoryKey=$repository") {
                authorized()
            }.andExpect { status { isForbidden() } }
        } finally {
            userAccess.actor = ActorIdentity.github(42, "lim")
            userAccess.role = RepositoryRole.MAINTAINER
        }
    }

    @Test
    fun `목록 범위와 맞지 않는 상태나 잘못된 페이지 입력은 거부한다`() {
        for (query in listOf("status=DRAFT", "scope=MY_DRAFTS&status=PUBLISHED", "page=-1", "size=0", "size=51", "path=../App.kt", "page=0&cursor=invalid", "size=10&q=검색", "scope=MY_DRAFTS&limit=10")) {
            mockMvc.get("/api/v1/change-records?repositoryKey=acme/intent-trace&$query") {
                authorized()
            }.andExpect { status { isBadRequest() } }
        }
    }

    @Test
    fun `기여자는 REST로 기록을 만들고 잘못된 중첩 입력은 거부된다`() {
        mockMvc.post("/api/v1/change-records") {
            authorized()
            contentType = MediaType.APPLICATION_JSON
            content = createRequest(UUID.randomUUID().toString(), "판단을 기록한다.")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.createdBy.subject") { value("github:42") }
        }

        mockMvc.post("/api/v1/change-records") {
            authorized()
            contentType = MediaType.APPLICATION_JSON
            content = createRequest(UUID.randomUUID().toString(), "")
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `비밀값 제거 후 제목 길이가 초과되면 원문 없이 입력 오류를 반환한다`() {
        mockMvc.post("/api/v1/change-records") {
            authorized()
            contentType = MediaType.APPLICATION_JSON
            content = createRequest(UUID.randomUUID().toString(), "판단을 기록한다.")
                .replace("REST 인증 계약", "password=x ".repeat(18))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("비밀값 제거 후 제목 길이는 200자 이하여야 합니다.") }
        }
    }

    @Test
    fun `요청 식별자의 비밀값은 저장 전에 원문 없이 거부한다`() {
        val sessionToken = "its_${"A".repeat(43)}"

        mockMvc.post("/api/v1/change-records") {
            authorized()
            contentType = MediaType.APPLICATION_JSON
            content = createRequest(sessionToken, "판단을 기록한다.")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("요청 식별자에는 비밀값이나 개인 절대 경로를 넣을 수 없습니다.") }
            content { string(not(containsString(sessionToken))) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["가", "\u0001"])
    fun `입력 상한의 한글과 JSON 이스케이프 기록은 4MiB 안에서 상세를 반환한다`(character: String) {
        val digest = "a".repeat(64)
        val timestamp = Instant.parse("2026-08-31T00:00:00Z")
        val path = ("가".repeat(49) + "/").repeat(19) + "가".repeat(50)
        val request = CreateChangeRecordRequest(
            requestId = UUID.randomUUID().toString(),
            repositoryKey = "acme/intent-trace",
            snapshotDigest = digest,
            title = character.repeat(200),
            requestSummary = character.repeat(2000),
            decisions = List(20) {
                DecisionRequest(character.repeat(1000), character.repeat(2000), PurposeSource.STATED_BY_USER)
            },
            codeAnchors = List(100) {
                CodeAnchorRequest(path, character.repeat(500), 1, 1, digest)
            },
            verifications = List(50) {
                VerificationRequest(character.repeat(2000), 0, timestamp, timestamp, digest, digest, character.repeat(2000))
            },
            openQuestions = List(50) { character.repeat(1000) },
        )
        val created = mockMvc.post("/api/v1/change-records") {
            authorized()
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(request)
        }.andExpect { status { isCreated() } }.andReturn()
        val id = objectMapper.readTree(created.response.contentAsByteArray).get("id").stringValue()
        val detail = mockMvc.get("/api/v1/change-records/$id") {
            authorized()
        }.andExpect { status { isOk() } }.andReturn()

        val responseBytes = detail.response.contentAsByteArray.size
        assertTrue(responseBytes > 1_000_000)
        assertTrue(responseBytes <= 4 * 1024 * 1024, "단건 응답이 IntelliJ의 4MiB 상한을 넘습니다: $responseBytes")
    }

    @Test
    fun `인증 실패와 GitHub 사용자 조회 장애를 구분한다`() {
        mockMvc.get("/api/v1/change-records/${UUID.randomUUID()}")
            .andExpect { status { isUnauthorized() } }

        userAccess.failAuthentication = true
        try {
            mockMvc.get("/api/v1/change-records/${UUID.randomUUID()}") {
                authorized()
            }.andExpect { status { isBadGateway() } }
        } finally {
            userAccess.failAuthentication = false
        }
    }

    @Test
    fun `읽기 권한 사용자는 기록을 만들 수 없다`() {
        userAccess.role = RepositoryRole.READER
        try {
            mockMvc.post("/api/v1/change-records") {
                authorized()
                contentType = MediaType.APPLICATION_JSON
                content = createRequest(UUID.randomUUID().toString(), "판단을 기록한다.")
            }.andExpect { status { isForbidden() } }
        } finally {
            userAccess.role = RepositoryRole.MAINTAINER
        }
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.authorized() {
        header(HttpHeaders.AUTHORIZATION, "Bearer ghu_authenticated-rest-test")
    }

    private fun createRequest(requestId: String, decisionSummary: String): String =
        """
        {
          "requestId":"$requestId",
          "repositoryKey":"acme/intent-trace",
          "snapshotDigest":"${"a".repeat(64)}",
          "title":"REST 인증 계약",
          "requestSummary":"인증과 권한 상태를 확인한다.",
          "decisions":[{"summary":"$decisionSummary","source":"STATED_BY_USER"}],
          "codeAnchors":[{
            "relativePath":"src/App.kt",
            "startLine":1,
            "endLine":2,
            "contentHash":"${"b".repeat(64)}"
          }]
        }
        """.trimIndent()

    @TestConfiguration
    class RestTestConfiguration {
        @Bean
        @Primary
        fun gitHubUserAccessGateway(): TestGitHubUserAccessGateway = TestGitHubUserAccessGateway()
    }

    class TestGitHubUserAccessGateway : GitHubUserAccessGateway {
        var failAuthentication = false
        var role: RepositoryRole? = RepositoryRole.MAINTAINER
        var actor = ActorIdentity.github(42, "lim")

        override fun authenticate(accessToken: String): ActorIdentity {
            if (failAuthentication) throw GitHubIdentityApiException("테스트 사용자 조회 장애")
            return actor
        }

        override fun repositoryRole(
            accessToken: String,
            actor: ActorIdentity,
            repository: GitHubRepository,
        ): RepositoryRole? = role
    }
}
