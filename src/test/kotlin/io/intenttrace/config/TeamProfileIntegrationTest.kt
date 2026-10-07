package io.intenttrace.config

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.registry.HealthContributorRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// team 프로필은 postgres 프로필의 DB 환경 변수를 읽으므로 테스트용 H2 연결을 직접 지정한다.
@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:team;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
])
@ActiveProfiles("team")
@AutoConfigureMockMvc
class TeamProfileIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val registry: HealthContributorRegistry,
    @Autowired private val environment: Environment,
) {
    @Test
    fun `team profile은 외부 reverse proxy와 비활성 H2 console을 사용한다`() {
        assertTrue(environment.activeProfiles.contains("postgres"))
        assertEquals("0.0.0.0", environment.getProperty("server.address"))
        assertEquals("framework", environment.getProperty("server.forward-headers-strategy"))
        assertFalse(environment.getProperty("spring.h2.console.enabled", Boolean::class.java, true))
    }

    @Test
    fun `DB 장애는 요청 수신만 중단하고 프로세스 재시작 신호로 사용하지 않는다`() {
        mvc.get("/actuator/health/readiness").andExpect { status { isOk() } }

        val databaseHealth = requireNotNull(registry.unregisterContributor("db"))
        try {
            registry.registerContributor("db", HealthIndicator { Health.down().withDetail("test", "private-database-detail").build() })
            mvc.get("/actuator/health/readiness").andExpect {
                status { isServiceUnavailable() }
                content { json("""{"status":"DOWN"}""", JsonCompareMode.STRICT) }
            }
            mvc.get("/actuator/health/liveness").andExpect { status { isOk() } }
        } finally {
            registry.unregisterContributor("db")
            registry.registerContributor("db", databaseHealth)
        }

        mvc.get("/actuator/health/readiness").andExpect { status { isOk() } }
    }
}
