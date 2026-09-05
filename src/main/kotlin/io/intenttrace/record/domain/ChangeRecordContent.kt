package io.intenttrace.record.domain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.HexFormat

data class ChangeRecordContent(
    val baseRevision: String?,
    val snapshotDigest: String,
    val title: String,
    val requestSummary: String,
    val decisions: List<Decision>,
    val codeAnchors: List<CodeAnchor>,
    val verifications: List<VerificationRun>,
    val openQuestions: List<String>,
) {
    fun digest(): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun text(value: String?) {
                output.writeBoolean(value != null)
                if (value != null) {
                    val encoded = value.toByteArray(Charsets.UTF_8)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
            }
            text(baseRevision); text(snapshotDigest); text(title); text(requestSummary)
            output.writeInt(decisions.size)
            decisions.forEach { text(it.summary); text(it.rationale); text(it.source.name) }
            output.writeInt(codeAnchors.size)
            codeAnchors.forEach {
                text(it.relativePath); text(it.symbolName)
                output.writeInt(it.startLine); output.writeInt(it.endLine); text(it.contentHash)
            }
            output.writeInt(verifications.size)
            verifications.forEach {
                text(it.command); output.writeInt(it.exitCode)
                text(it.startedAt.toString()); text(it.finishedAt.toString())
                text(it.snapshotDigest); text(it.outputDigest); text(it.summary)
            }
            output.writeInt(openQuestions.size)
            openQuestions.forEach(::text)
            if (codeAnchors.any { it.side != CodeSide.TARGET || it.relatedPath != null } ||
                verifications.any { it.source != VerificationSource.CLIENT_REPORTED }) {
                text("evidence-v2")
                codeAnchors.forEach { text(it.side.name); text(it.relatedPath) }
                verifications.forEach { text(it.source.name) }
            }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
    }
}
