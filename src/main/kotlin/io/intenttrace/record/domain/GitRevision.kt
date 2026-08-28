package io.intenttrace.record.domain

const val FULL_GIT_REVISION_PATTERN = "^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$"

@JvmInline
value class GitRevision private constructor(
    val value: String,
) {
    companion object {
        private val FULL_REVISION = Regex(FULL_GIT_REVISION_PATTERN)

        fun parse(value: String): GitRevision {
            require(FULL_REVISION.matches(value)) {
                "전체 Git 커밋 ID는 40자 또는 64자 16진수여야 합니다."
            }
            return GitRevision(value.lowercase())
        }
    }
}
