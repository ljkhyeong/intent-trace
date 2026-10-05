package io.intenttrace.record.application

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.domain.ChangeRecord

// 운영 코드는 소유권 확인 때 읽은 기록을 넘긴다. 테스트에서는 ID로 다시 읽는다.
fun ChangeRecordFacade.confirm(command: ConfirmChangeRecordCommand, actor: ActorIdentity): ChangeRecord =
    confirm(get(command.recordId), command, actor)

fun ChangeRecordFacade.publish(command: PublishChangeRecordCommand, actor: ActorIdentity): ChangeRecord =
    publish(get(command.recordId), command, actor)
