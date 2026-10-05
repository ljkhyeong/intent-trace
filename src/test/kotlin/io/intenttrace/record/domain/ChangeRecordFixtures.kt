package io.intenttrace.record.domain

import io.intenttrace.identity.domain.ActorIdentity
import java.time.Instant
import java.util.UUID

/** 상태 전이·권한 테스트용 초안이다. 다른 상태는 copy로 만든다. */
fun draftRecord(createdBy: ActorIdentity, repositoryKey: String = "acme/intent-trace"): ChangeRecord = ChangeRecord(
    id = UUID.randomUUID(), requestId = "test-request", repositoryKey = repositoryKey, targetRevision = null,
    snapshotDigest = "a".repeat(64), title = "변경 의도 기록", requestSummary = "요청과 검증을 남긴다.",
    status = ChangeRecordStatus.DRAFT, createdBy = createdBy, createdAt = Instant.parse("2026-08-27T12:00:00Z"),
    confirmedAt = null, publishedAt = null, supersededBy = null, version = 0,
    decisions = listOf(Decision("작성자 확인 후 공개한다.", null, PurposeSource.STATED_BY_USER)),
    codeAnchors = listOf(CodeAnchor("src/App.kt", "App", 1, 2, "b".repeat(64))),
    verifications = emptyList(), openQuestions = emptyList(), creationDigest = "d".repeat(64),
)
