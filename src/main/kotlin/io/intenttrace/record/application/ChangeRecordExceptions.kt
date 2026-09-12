package io.intenttrace.record.application

import java.util.UUID

class ChangeRecordNotFoundException(id: UUID) :
    RuntimeException("변경 기록을 찾을 수 없습니다: $id")

class ConcurrentChangeRecordUpdateException(id: UUID) :
    RuntimeException("다른 요청이 기록을 먼저 수정했습니다: $id. 최신 기록을 다시 조회하세요.")

class ChangeRecordRequestConflictException :
    RuntimeException("요청 ID가 다른 작성자·저장소·내용의 기록에 이미 사용됐습니다.")

class ChangeRecordOwnershipException : RuntimeException("기록을 만든 작성자만 이 작업을 수행할 수 있습니다.")
