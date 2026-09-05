package io.intenttrace.record.application

import io.intenttrace.config.GitHubProperties
import io.intenttrace.record.domain.ChangeRecord
import io.intenttrace.record.domain.PurposeSource
import io.intenttrace.record.domain.CodeSide
import io.intenttrace.record.domain.VerificationSource
import org.springframework.web.util.UriUtils
import org.springframework.stereotype.Component

@Component
class ChangeRecordMarkdownRenderer(private val properties: GitHubProperties = GitHubProperties()) {
    fun render(record: ChangeRecord): String = buildString {
        appendLine("# 변경 의도: ${plainText(record.title)}")
        appendLine()
        appendLine("- 상태: ${inlineCode(record.status.toString())}")
        appendLine("- 저장소: ${inlineCode(record.repositoryKey)}")
        appendLine("- 커밋: ${inlineCode(record.targetRevision ?: "작성자 확인 전")}")
        appendLine("- 스냅샷 해시: ${inlineCode(record.snapshotDigest)}")
        appendLine("- 작성자: ${inlineCode("@${record.createdBy.login}")} (${inlineCode(record.createdBy.subject)})")
        val recordUrl = properties.userAuthorization.callbackUrl.resolve("/records/${record.id}")
        appendLine("- 기록 열람: [브라우저에서 읽기]($recordUrl)")
        record.derivedFromRecordId?.let {
            val url = properties.userAuthorization.callbackUrl.resolve("/records/$it")
            appendLine("- 원본 공개 기록: [기록 읽기]($url)")
        }
        record.supersededBy?.let {
            val url = properties.userAuthorization.callbackUrl.resolve("/records/$it")
            appendLine("- 대체 기록: [$it]($url) — 저장소 접근 권한이 필요합니다.")
        }
        appendLine()
        appendLine("## 요청")
        appendLine()
        appendLine(plainText(record.requestSummary))
        appendLine()
        appendLine("## 구현 결정과 이유")
        appendLine()
        record.decisions.forEach { decision ->
            append("- ${plainText(decision.summary)} — ${decision.source.label}")
            decision.rationale?.takeIf(String::isNotBlank)?.let { append("\n  - 근거: ${plainText(it)}") }
            appendLine()
        }
        appendLine()
        appendLine("## 관련 코드")
        appendLine()
        record.codeAnchors.forEach { anchor ->
            val symbol = anchor.symbolName?.let { " (${inlineCode(it)})" }.orEmpty()
            val ref = if (anchor.side == CodeSide.BASE) record.baseRevision else record.targetRevision
            val label = "${anchor.relativePath}:${anchor.startLine}-${anchor.endLine}"
            val side = if (anchor.side == CodeSide.BASE) "변경 전" else "변경 후"
            val path = anchor.relativePath.split('/').joinToString("/") { UriUtils.encodePathSegment(it, Charsets.UTF_8).replace("(", "%28").replace(")", "%29") }
            val link = ref?.let { "[${inlineCode(label)}](${properties.userAuthorization.webBaseUrl.resolve("/${record.repositoryKey}/blob/$it/$path")}#L${anchor.startLine}-L${anchor.endLine})" } ?: inlineCode(label)
            appendLine("- $side $link$symbol — ${inlineCode(anchor.contentHash)}")
            anchor.relatedPath?.let { appendLine("  - 반대쪽 연결 경로: ${inlineCode(it)}") }
        }
        appendLine()
        appendLine("코드·검증 해시는 클라이언트 제출값입니다. 서버 코드 확인 결과는 별도 조회하며 테스트 실행을 증명하지 않습니다.")
        appendLine()
        appendLine("## 검증")
        appendLine()
        if (record.verifications.isEmpty()) {
            appendLine("- 등록된 검증 결과가 없습니다.")
        } else {
            record.verifications.forEach { verification ->
                val state = when {
                    !verification.isCurrentFor(record) -> "다른 스냅샷의 결과"
                    verification.exitCode == 0 -> "통과"
                    else -> "실패"
                }
                appendLine(
                    "- **$state** ${inlineCode(verification.command)} — ${plainText(verification.summary)}",
                )
                val origin = if (verification.source == VerificationSource.LOCAL_RUNNER_REPORTED) "로컬 실행 도구가 수집했다고 보고함" else "클라이언트가 제출함"
                appendLine("  - 출처: $origin / 출력 해시: ${inlineCode(verification.outputDigest)}")
            }
        }
        appendLine()
        appendLine("## 미검증·남은 질문")
        appendLine()
        if (record.openQuestions.isEmpty()) {
            appendLine("- 현재 기록된 항목이 없습니다.")
        } else {
            record.openQuestions.forEach { appendLine("- ${plainText(it)}") }
        }
    }

    private fun plainText(value: String): String = buildString(value.length) {
        normalizedSingleLine(value).forEach { character ->
            if (character.isAsciiPunctuation()) append('\\')
            append(character)
        }
    }

    private fun inlineCode(value: String): String {
        val normalized = normalizedSingleLine(value)
        val longestBacktickRun = BACKTICK_RUN.findAll(normalized)
            .maxOfOrNull { it.value.length }
            ?: 0
        val delimiter = "`".repeat(longestBacktickRun + 1)
        val content = if (normalized.startsWith('`') || normalized.endsWith('`')) " $normalized " else normalized
        return "$delimiter$content$delimiter"
    }

    private fun normalizedSingleLine(value: String): String = value
        .trim()
        .replace(LINE_BREAK, " ")
        .replace('\t', ' ')

    private fun Char.isAsciiPunctuation(): Boolean =
        code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126

    private val PurposeSource.label: String
        get() = when (this) {
            PurposeSource.STATED_BY_USER -> "사용자가 명시함"
            PurposeSource.STATED_IN_COMMIT -> "커밋에 명시됨"
            PurposeSource.CONFIRMED_AI_SUMMARY -> "작성자가 AI 요약을 확인함"
            PurposeSource.INFERRED -> "정황에서 추론함"
            PurposeSource.UNKNOWN -> "근거를 확인하지 못함"
        }

    companion object {
        private val LINE_BREAK = Regex("\\R+")
        private val BACKTICK_RUN = Regex("`+")
    }
}
