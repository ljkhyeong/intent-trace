package io.intenttrace.identity.application

import java.net.URI

object BrowserReturnPath {
    private val allowedPath = Regex("^/records(?:/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})?$")

    fun validate(value: String): String {
        require(value.length <= 2048 && value.none { it.isISOControl() }) { "기록으로 돌아갈 주소가 올바르지 않습니다." }
        val uri = try { URI(value) } catch (_: Exception) {
            throw IllegalArgumentException("기록으로 돌아갈 주소가 올바르지 않습니다.")
        }
        require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawFragment == null && allowedPath.matches(uri.rawPath.orEmpty())) {
            "기록 화면 안에서만 이동할 수 있습니다."
        }
        return value
    }
}
