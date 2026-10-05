package io.intenttrace.record.domain

const val FULL_GIT_REVISION_PATTERN = "^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$"

private val FULL_GIT_REVISION = Regex(FULL_GIT_REVISION_PATTERN)

/** 40자(SHA-1) 또는 64자(SHA-256) 전체 커밋 ID를 소문자로 정규화한다. */
fun requireFullRevision(value: String): String {
    require(FULL_GIT_REVISION.matches(value)) { "전체 Git 커밋 ID는 40자 또는 64자 16진수여야 합니다." }
    return value.lowercase()
}
