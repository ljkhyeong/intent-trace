package io.intenttrace

import org.springframework.web.util.HtmlUtils

/** 화면 HTML에서 [pattern]의 첫 그룹으로 찾은 href를 이스케이프 해제해 돌려준다. */
fun htmlHref(body: String, pattern: String): String =
    HtmlUtils.htmlUnescape(requireNotNull(Regex(pattern).find(body)) { "링크를 찾지 못했습니다: $pattern" }.groupValues[1])

/** 링크 문구가 정확히 [label]인 a 태그의 href다. */
fun htmlLink(body: String, label: String): String = htmlHref(body, "href=\"([^\"]+)\">${Regex.escape(label)}</a>")
