package io.intenttrace.config

import io.intenttrace.IntentTraceApplication
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [IntentTraceApplication::class],
    properties = [
        "spring.datasource.url=jdbc:h2:mem:team-profile;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
    ],
)
@ActiveProfiles("team")
class TeamProfileConfigurationTest(
    @Autowired private val environment: Environment,
) {
    @Test
    fun `team profile은 외부 reverse proxy와 비활성 H2 console을 사용한다`() {
        assertTrue(environment.activeProfiles.contains("postgres"))
        assertEquals("0.0.0.0", environment.getProperty("server.address"))
        assertEquals("framework", environment.getProperty("server.forward-headers-strategy"))
        assertFalse(environment.getProperty("spring.h2.console.enabled", Boolean::class.java, true))
        assertTrue(environment.getProperty("management.endpoint.health.probes.enabled", Boolean::class.java, false))
    }
}
