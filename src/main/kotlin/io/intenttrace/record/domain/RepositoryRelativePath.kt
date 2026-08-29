package io.intenttrace.record.domain

import java.nio.file.Path

fun requireRepositoryRelativePath(value: String): String {
    val path = runCatching { Path.of(value) }
        .getOrElse { throw IllegalArgumentException(PATH_ERROR_MESSAGE) }
    require(
        value.isNotBlank() &&
            !path.isAbsolute &&
            !WINDOWS_ABSOLUTE_PATH.matches(value) &&
            '\\' !in value &&
            path.none { it.toString() == ".." },
    ) { PATH_ERROR_MESSAGE }

    val normalized = path.normalize().toString()
    require(normalized.isNotBlank() && normalized != ".") { PATH_ERROR_MESSAGE }
    return normalized
}

private const val PATH_ERROR_MESSAGE = "코드 경로는 저장소 기준 상대 경로여야 합니다."
private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[/\\\\].*")
