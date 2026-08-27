package io.intenttrace.record.application

import java.util.UUID

class ChangeRecordNotFoundException(id: UUID) :
    RuntimeException("변경 의도 기록을 찾을 수 없습니다: $id")

class ConcurrentChangeRecordUpdateException(id: UUID) :
    RuntimeException("다른 요청이 변경 의도 기록을 먼저 수정했습니다: $id")
