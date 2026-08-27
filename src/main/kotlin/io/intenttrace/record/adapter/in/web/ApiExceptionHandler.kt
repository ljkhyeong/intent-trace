package io.intenttrace.record.adapter.`in`.web

import io.intenttrace.record.application.ChangeRecordNotFoundException
import io.intenttrace.record.application.ConcurrentChangeRecordUpdateException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ChangeRecordNotFoundException::class)
    fun notFound(exception: ChangeRecordNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "변경 의도 기록 없음", exception.message)

    @ExceptionHandler(ConcurrentChangeRecordUpdateException::class)
    fun conflict(exception: ConcurrentChangeRecordUpdateException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "기록 버전 충돌", exception.message)

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidInput(exception: IllegalArgumentException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "변경 의도 기록 값 오류", exception.message)

    @ExceptionHandler(IllegalStateException::class)
    fun invalidState(exception: IllegalStateException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "변경 의도 기록 처리 실패", exception.message)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(exception: MethodArgumentNotValidException): ProblemDetail {
        val detail = exception.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return problem(HttpStatus.BAD_REQUEST, "요청 값 검증 실패", detail)
    }

    private fun problem(status: HttpStatus, title: String, detail: String?): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: title).also { it.title = title }
}
