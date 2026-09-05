package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.identity.application.MySessions
import io.intenttrace.identity.application.SessionChannel
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.application.ActivityVisibility
import io.intenttrace.record.application.RecordActivities
import io.intenttrace.record.application.RecordOperation

internal fun RecordBrowserPage.sessions(actor: ActorIdentity, result: MySessions): String = layout("내 연결 관리", actor, buildString {
    append("<header class=\"page-heading\"><h1>내 연결 관리</h1><p>최근 사용과 만료 시각을 확인하고 사용하지 않는 연결을 종료하세요.</p></header><ul class=\"records\">")
    result.sessions.forEach { session ->
        append("<li><div><span class=\"status\">${if (session.current) "현재 연결" else "다른 연결"}</span><h2>${if (session.channel == SessionChannel.BROWSER) "브라우저" else "Agent·API"} 연결 · ${session.id.toString().take(8)}</h2>")
        append("<dl><dt>생성</dt><dd>${stamp(session.createdAt)}</dd><dt>최근 사용</dt><dd>${stamp(session.lastUsedAt)}</dd><dt>만료</dt><dd>${stamp(session.expiresAt)}</dd></dl>")
        append("<form method=\"post\" action=\"/records/sessions/${session.id}/revoke\"><button class=\"secondary\">${if (session.current) "현재 연결 종료·로그아웃" else "이 연결 종료"}</button></form></div></li>")
    }
    append("</ul><section class=\"session-actions\"><h2>모든 연결 종료</h2><p>현재 브라우저와 연결된 모든 Agent·API가 로그아웃됩니다. 다시 사용하려면 로그인해야 합니다.</p><form method=\"post\" action=\"/records/sessions/revoke-all\"><button>모든 연결 종료·로그아웃</button></form></section>")
})

internal fun RecordBrowserPage.activities(actor: ActorIdentity, result: RecordActivities): String = layout("기록 변경 이력", actor, buildString {
    append("<a class=\"back-link\" href=\"/records/${result.recordId}\">기록으로 돌아가기</a><header class=\"page-heading\"><h1>기록 변경 이력</h1><p>저장에 성공한 작업과 처리 시각입니다. 이전 본문은 저장하지 않습니다.</p></header>")
    if (result.visibility == ActivityVisibility.TEAM) append("<aside class=\"notice\">팀원에게는 공개·대체 작업만 표시합니다. 비공개 작업 이력은 작성자만 읽습니다.</aside>")
    if (result.historyStartsAtCreation == false) append("<aside class=\"notice\">이력 수집 시작 전 작업은 확인할 수 없습니다. 과거 작업을 추정해서 채우지 않습니다.</aside>")
    if (result.items.isEmpty()) append("<p class=\"empty\">표시할 변경 이력이 없습니다.</p>")
    append("<ol class=\"records\">")
    result.items.forEach { activity ->
        val operation = when (activity.operation) {
            RecordOperation.CREATE -> "초안 생성"; RecordOperation.REVISE -> "초안 수정"; RecordOperation.CONFIRM -> "작성자 확인"
            RecordOperation.REOPEN -> "작성자 확인 취소"; RecordOperation.PUBLISH -> "팀 공개"; RecordOperation.DISCARD -> "기록 폐기"
            RecordOperation.SUPERSEDE -> "후속 기록으로 대체"
        }
        append("<li><div><span class=\"status\">버전 ${activity.version}</span><h2>$operation</h2><p>${stamp(activity.occurredAt)}</p><p>처리한 사용자: ${html(activity.actorSubject)}</p>")
        append("<p>${activity.previousStatus?.let { "${it.label} → " }.orEmpty()}${activity.status.label}</p></div></li>")
    }
    append("</ol>")
    result.nextBeforeVersion?.let { append("<nav class=\"pagination\"><a class=\"button secondary\" href=\"${html(url("/records/${result.recordId}/activities", "beforeVersion" to it.toString()))}\">이전 작업 더 보기</a></nav>") }
})
