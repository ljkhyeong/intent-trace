package io.intenttrace.record.application

import io.intenttrace.record.domain.ChangeRecordStatus
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:intent-trace-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.h2.console.enabled=false",
    ],
)
class ChangeRecordFacadeIntegrationTest(
    @Autowired private val facade: ChangeRecordFacade,
) {
    @Test
    fun `같은 요청은 한 번만 만들고 공개 기록을 코드 줄로 찾는다`() {
        val command = CreateChangeRecordCommand(
            requestId = "integration-turn-1",
            repositoryKey = "intent-trace",
            baseRevision = null,
            snapshotDigest = digest,
            title = "변경 의도 저장",
            requestSummary = "API_KEY=secret-value 요청을 안전하게 요약한다.",
            createdBy = "lim",
            decisions = listOf(Decision("작성자 확인 후 공개한다.", null, PurposeSource.STATED_BY_USER)),
            codeAnchors = listOf(CodeAnchor("src/App.kt", "App", 10, 20, "d".repeat(64))),
            verifications = emptyList(),
            openQuestions = listOf("GitHub 게시 자동화는 아직 검증하지 않았다."),
        )

        val first = facade.create(command)
        val retried = facade.create(command)
        val confirmed = facade.confirm(
            ConfirmChangeRecordCommand(first.id, first.version, "lim", revision, digest),
        )
        val published = facade.publish(
            PublishChangeRecordCommand(confirmed.id, confirmed.version, digest),
        )
        val found = facade.findIntent("intent-trace", revision, "src/App.kt", 15)

        assertEquals(first.id, retried.id)
        assertEquals("API_KEY=[REDACTED] 요청을 안전하게 요약한다.", first.requestSummary)
        assertEquals(ChangeRecordStatus.PUBLISHED, published.status)
        assertEquals(listOf(published.id), found.map { it.id })
    }

    companion object {
        private val digest = "a".repeat(64)
        private val revision = "b".repeat(40)
    }
}
