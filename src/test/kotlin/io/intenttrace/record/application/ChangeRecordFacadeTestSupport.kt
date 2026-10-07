package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.CodeAnchor
import io.intenttrace.record.domain.Decision
import io.intenttrace.record.domain.PurposeSource
import java.util.UUID

// 운영 코드는 소유권 확인 때 읽은 기록을 넘긴다. 테스트에서는 ID로 다시 읽는다.
fun ChangeRecordFacade.confirm(command: ConfirmChangeRecordCommand, actor: ActorIdentity): ChangeRecord =
    confirm(get(command.recordId), command, actor)

fun ChangeRecordFacade.publish(command: PublishChangeRecordCommand, actor: ActorIdentity): ChangeRecord =
    publish(get(command.recordId), command, actor)

/** 저장·조회 테스트용 생성 명령이다. 테스트가 확인하는 다른 값은 copy로 바꾼다. */
fun createCommand(
    repositoryKey: String = "acme/intent-trace",
    title: String = "변경 의도 기록",
    requestId: String = UUID.randomUUID().toString(),
) = CreateChangeRecordCommand(
    requestId, repositoryKey, null, "a".repeat(64), title, "요청과 검증을 남긴다.",
    listOf(Decision("작성자 확인 후 공개한다.", null, PurposeSource.STATED_BY_USER)),
    listOf(CodeAnchor("src/App.kt", null, 1, 2, "c".repeat(64))), emptyList(), emptyList(),
)

/** 확인·공개 단계를 검증하지 않는 테스트에서 공개 기록을 준비한다. 요청 ID는 호출마다 달라야 한다. */
fun ChangeRecordFacade.createPublished(
    command: CreateChangeRecordCommand,
    actor: ActorIdentity,
    revision: String = "b".repeat(40),
): ChangeRecord {
    val draft = create(command, actor)
    val confirmed = confirm(draft, ConfirmChangeRecordCommand(draft.id, draft.version, revision, draft.snapshotDigest), actor)
    return publish(confirmed, PublishChangeRecordCommand(draft.id, confirmed.version, draft.snapshotDigest), actor)
}

fun TeamChangeRecordService.createPublished(command: CreateChangeRecordCommand, revision: String = "b".repeat(40)): ChangeRecord {
    val draft = create(command)
    val confirmed = confirm(ConfirmChangeRecordCommand(draft.id, draft.version, revision, draft.snapshotDigest))
    return publish(PublishChangeRecordCommand(draft.id, confirmed.version, draft.snapshotDigest))
}
