package io.intenttrace.identity.domain

import java.util.Locale

data class ActorIdentity(
    val subject: String,
    val login: String,
) {
    init {
        require(subject.isNotBlank() && subject.length <= 160) { "작성자 subject 형식이 올바르지 않습니다." }
        require(login.isNotBlank() && login.length <= 120) { "작성자 login 형식이 올바르지 않습니다." }
    }

    // JSON 응답에 필드가 늘지 않도록 속성 대신 함수로 둔다.
    fun githubUserIdOrNull(): Long? = subject.removePrefix(GITHUB_SUBJECT_PREFIX).toLongOrNull()

    companion object {
        private const val GITHUB_SUBJECT_PREFIX = "github:"

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

enum class RepositoryRole(private val level: Int) {
    READER(1),
    CONTRIBUTOR(2),
    MAINTAINER(3),
    ;

    fun allows(required: RepositoryRole): Boolean = level >= required.level
}
