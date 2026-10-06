package io.intenttrace.record.domain

import java.nio.file.Path

fun requireRepositoryRelativePath(value: String): String {
    val path = runCatching { Path.of(value) }
        .getOrElse { throw IllegalArgumentException(PATH_ERROR_MESSAGE) }
    require(
        value.isNotBlank() &&
            !path.isAbsolute &&
            !WINDOWS_DRIVE_PATH.containsMatchIn(value) &&
            '\\' !in value &&
            value.none(Char::isISOControl) &&
            path.none { it.toString() == ".." },
    ) { PATH_ERROR_MESSAGE }

    val normalized = path.normalize().joinToString("/")
    require(normalized.isNotBlank() && normalized != ".") { PATH_ERROR_MESSAGE }
    return normalized
}

private const val PATH_ERROR_MESSAGE = "코드 경로는 저장소 기준 상대 경로여야 합니다."
// Path.isAbsolute는 서버 운영체제 기준이므로 Windows 드라이브 경로를 따로 막는다. 역슬래시는 위에서 거부한다.
private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:/")
