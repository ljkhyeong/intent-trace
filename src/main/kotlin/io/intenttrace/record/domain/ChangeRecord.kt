package io.intenttrace.record.domain

import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.identity.domain.GitHubRepository
import java.time.Instant
import java.util.UUID

data class ChangeRecord(
    val id: UUID,
    val requestId: String,
    val repositoryKey: String,
    val baseRevision: String?,
    val targetRevision: String?,
    val snapshotDigest: String,
    val title: String,
    val requestSummary: String,
    val status: ChangeRecordStatus,
    val createdBy: ActorIdentity,
    val createdAt: Instant,
    val confirmedAt: Instant?,
    val publishedAt: Instant?,
    val supersededBy: UUID?,
    val version: Long,
    val decisions: List<Decision>,
    val codeAnchors: List<CodeAnchor>,
    val verifications: List<VerificationRun>,
    val openQuestions: List<String>,
    val creationDigest: String? = null,
    val derivedFromRecordId: UUID? = null,
) {
    fun content(): ChangeRecordContent = ChangeRecordContent(
        baseRevision, snapshotDigest, title, requestSummary, decisions, codeAnchors, verifications, openQuestions, derivedFromRecordId,
    )

    fun requireSuccessorSource(actor: ActorIdentity) {
        check(status == ChangeRecordStatus.PUBLISHED || status == ChangeRecordStatus.SUPERSEDED) {
            "공개하거나 대체된 기록에서만 후속 초안을 만들 수 있습니다."
        }
        check(actor.subject == createdBy.subject) { "작성자만 후속 초안을 만들 수 있습니다." }
    }

    fun revise(actor: ActorIdentity, content: ChangeRecordContent): ChangeRecord {
        check(status == ChangeRecordStatus.DRAFT) { "초안만 수정할 수 있습니다. 확인한 기록은 먼저 확인을 취소하세요." }
        check(actor.subject == createdBy.subject) { "작성자만 초안을 수정할 수 있습니다." }
        return copy(
            baseRevision = content.baseRevision, snapshotDigest = content.snapshotDigest,
            title = content.title, requestSummary = content.requestSummary,
            decisions = content.decisions, codeAnchors = content.codeAnchors,
            verifications = content.verifications, openQuestions = content.openQuestions,
            creationDigest = creationDigest ?: content().digest(), version = version + 1,
        )
    }

    fun reopen(actor: ActorIdentity): ChangeRecord {
        check(status == ChangeRecordStatus.AUTHOR_CONFIRMED) { "작성자가 확인한 비공개 기록만 확인을 취소할 수 있습니다." }
        check(actor.subject == createdBy.subject) { "작성자만 확인을 취소할 수 있습니다." }
        return copy(status = ChangeRecordStatus.DRAFT, targetRevision = null, confirmedAt = null, version = version + 1)
    }

    fun discard(actor: ActorIdentity): ChangeRecord {
        check(status == ChangeRecordStatus.DRAFT || status == ChangeRecordStatus.AUTHOR_CONFIRMED) {
            "비공개 초안과 확인 기록만 폐기할 수 있습니다."
        }
        check(actor.subject == createdBy.subject) { "작성자만 기록을 폐기할 수 있습니다." }
        return copy(status = ChangeRecordStatus.DISCARDED, version = version + 1)
    }

    fun confirm(actor: ActorIdentity, immutableRevision: String, currentSnapshotDigest: String, now: Instant): ChangeRecord {
        check(status == ChangeRecordStatus.DRAFT) { "초안 상태의 기록만 작성자가 확인할 수 있습니다." }
        check(actor.subject == createdBy.subject) { "기록을 만든 작성자만 확인할 수 있습니다." }
        check(snapshotDigest == currentSnapshotDigest) { "코드 스냅샷이 달라져 기록을 확인할 수 없습니다." }
        val revision = GitRevision.parse(immutableRevision)

        return copy(
            targetRevision = revision.value,
            status = ChangeRecordStatus.AUTHOR_CONFIRMED,
            confirmedAt = now,
            version = version + 1,
        )
    }

    fun publish(actor: ActorIdentity, currentSnapshotDigest: String, now: Instant): ChangeRecord {
        check(status == ChangeRecordStatus.AUTHOR_CONFIRMED) { "작성자가 확인한 기록만 공개할 수 있습니다." }
        check(actor.subject == createdBy.subject) { "기록을 만든 작성자만 공개할 수 있습니다." }
        check(snapshotDigest == currentSnapshotDigest) { "코드 스냅샷이 달라져 검증과 판단이 오래된 상태입니다." }
        check(targetRevision != null) { "전체 Git 커밋 ID가 없는 기록은 공개할 수 없습니다." }

        return copy(
            status = ChangeRecordStatus.PUBLISHED,
            publishedAt = now,
            version = version + 1,
        )
    }

    fun supersede(actor: ActorIdentity, replacement: ChangeRecord): ChangeRecord {
        check(status == ChangeRecordStatus.PUBLISHED) { "공개된 기록만 새 기록으로 대체할 수 있습니다." }
        check(replacement.status == ChangeRecordStatus.PUBLISHED) { "대체 기록도 먼저 공개되어야 합니다." }
        check(actor.subject == createdBy.subject && actor.subject == replacement.createdBy.subject) {
            "작성자가 만든 기록끼리만 대체할 수 있습니다."
        }
        check(GitHubRepository.parse(repositoryKey).key == GitHubRepository.parse(replacement.repositoryKey).key) {
            "같은 저장소의 기록으로만 대체할 수 있습니다."
        }
        check(id != replacement.id) { "기록이 자기 자신을 대체할 수 없습니다." }

        return copy(
            status = ChangeRecordStatus.SUPERSEDED,
            supersededBy = replacement.id,
            version = version + 1,
        )
    }

}

enum class ChangeRecordStatus {
    DRAFT,
    AUTHOR_CONFIRMED,
    PUBLISHED,
    SUPERSEDED,
    DISCARDED,
}

data class Decision(
    val summary: String,
    val rationale: String?,
    val source: PurposeSource,
)

enum class PurposeSource {
    STATED_BY_USER,
    STATED_IN_COMMIT,
    CONFIRMED_AI_SUMMARY,
    INFERRED,
    UNKNOWN,
}

data class CodeAnchor(
    val relativePath: String,
    val symbolName: String?,
    val startLine: Int,
    val endLine: Int,
    val contentHash: String,
    val side: CodeSide = CodeSide.TARGET,
    val relatedPath: String? = null,
) {
    init {
        requireRepositoryRelativePath(relativePath)
        relatedPath?.let(::requireRepositoryRelativePath)
        require(startLine > 0 && endLine >= startLine) { "코드 줄 범위가 올바르지 않습니다." }
        require(SHA_256.matches(contentHash)) { "코드 근거에는 SHA-256 해시가 필요합니다." }
    }

    companion object {
        private val SHA_256 = Regex("^[0-9a-fA-F]{64}$")
    }
}

enum class CodeSide { BASE, TARGET }

enum class VerificationSource { CLIENT_REPORTED, LOCAL_RUNNER_REPORTED }

data class VerificationRun(
    val command: String,
    val exitCode: Int,
    val startedAt: Instant,
    val finishedAt: Instant,
    val snapshotDigest: String,
    val outputDigest: String,
    val summary: String,
    val source: VerificationSource = VerificationSource.CLIENT_REPORTED,
) {
    init {
        require(!finishedAt.isBefore(startedAt)) { "검증 종료 시각은 시작 시각보다 빠를 수 없습니다." }
        require(SHA_256.matches(snapshotDigest)) { "검증 대상에는 SHA-256 스냅샷 해시가 필요합니다." }
        require(SHA_256.matches(outputDigest)) { "검증 결과에는 SHA-256 출력 해시가 필요합니다." }
    }

    fun isCurrentFor(record: ChangeRecord): Boolean = snapshotDigest == record.snapshotDigest

    companion object {
        private val SHA_256 = Regex("^[0-9a-fA-F]{64}$")
    }
}
