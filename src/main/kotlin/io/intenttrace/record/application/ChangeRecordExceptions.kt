package io.intenttrace.record.application

import java.util.UUID

class ChangeRecordNotFoundException(id: UUID) :
    RuntimeException("변경 의도 기록을 찾을 수 없습니다: $id")

class ConcurrentChangeRecordUpdateException(id: UUID) :
    RuntimeException("다른 요청이 변경 의도 기록을 먼저 수정했습니다: $id")

class ChangeRecordRequestConflictException(requestId: String) :
    RuntimeException("요청 식별자 $requestId 가 다른 작성자 또는 저장소에서 이미 사용됐습니다.")

class ChangeRecordOwnershipException : RuntimeException("기록을 만든 작성자만 이 작업을 수행할 수 있습니다.")
