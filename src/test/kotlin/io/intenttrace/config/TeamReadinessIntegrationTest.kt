package io.intenttrace.config

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.registry.HealthContributorRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:team-readiness-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
])
@ActiveProfiles("team")
@AutoConfigureMockMvc
class TeamReadinessIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val registry: HealthContributorRegistry,
) {
    @Test
    fun `DB 장애는 요청 수신만 중단하고 프로세스 재시작 신호로 사용하지 않는다`() {
        mvc.get("/actuator/health/readiness").andExpect { status { isOk() } }

        val databaseHealth = requireNotNull(registry.unregisterContributor("db"))
        try {
            registry.registerContributor("db", HealthIndicator { Health.down().withDetail("test", "private-database-detail").build() })
            mvc.get("/actuator/health/readiness").andExpect {
                status { isServiceUnavailable() }
                content { json("""{"status":"DOWN"}""", true) }
            }
            mvc.get("/actuator/health/liveness").andExpect { status { isOk() } }
        } finally {
            registry.unregisterContributor("db")
            registry.registerContributor("db", databaseHealth)
        }

        mvc.get("/actuator/health/readiness").andExpect { status { isOk() } }
    }
}
