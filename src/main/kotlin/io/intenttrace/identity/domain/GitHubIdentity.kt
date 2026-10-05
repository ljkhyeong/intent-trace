package io.intenttrace.identity.domain

import java.util.Locale

data class ActorIdentity(
    val subject: String,
    val login: String,
) {
    init {
        require(GITHUB_SUBJECT.matches(subject)) { "작성자 subject 형식이 올바르지 않습니다." }
        require(login.isNotBlank() && login.length <= 120) { "작성자 login 형식이 올바르지 않습니다." }
    }

    // JSON 응답에 필드가 늘지 않도록 속성 대신 함수로 둔다.
    fun githubUserId(): Long = subject.removePrefix(GITHUB_SUBJECT_PREFIX).toLong()

    companion object {
        private const val GITHUB_SUBJECT_PREFIX = "github:"
        private val GITHUB_SUBJECT = Regex("^github:[1-9][0-9]{0,18}$")

        fun github(userId: Long, login: String): ActorIdentity {
            require(userId > 0) { "GitHub 사용자 ID는 1 이상이어야 합니다." }
            return ActorIdentity(githubSubject(userId), login)
        }

        fun githubSubject(userId: Long): String = "$GITHUB_SUBJECT_PREFIX$userId"
    }
}

data class GitHubRepository(
    val owner: String,
    val name: String,
) {
    init {
        require(REPOSITORY_PART.matches(owner)) { "GitHub 저장소 소유자 형식이 올바르지 않습니다." }
        require(REPOSITORY_PART.matches(name) && !name.endsWith(".git")) {
            "GitHub 저장소 이름 형식이 올바르지 않습니다."
        }
    }

    val canonicalOwner: String = owner.lowercase(Locale.ROOT)
    val canonicalName: String = name.lowercase(Locale.ROOT)
    val key: String = "$canonicalOwner/$canonicalName"

    companion object {
        private val REPOSITORY_PART = Regex("^[A-Za-z0-9_.-]{1,100}$")

        fun parse(value: String): GitHubRepository {
            val parts = value.split('/')
            require(parts.size == 2) { "저장소 식별자는 owner/repository 형식이어야 합니다." }
            return GitHubRepository(parts[0], parts[1])
        }
    }
}

/** 선언 순서가 권한 크기다. */
enum class RepositoryRole {
    READER,
    CONTRIBUTOR,
    MAINTAINER,
    ;

    fun allows(required: RepositoryRole): Boolean = this >= required
}
