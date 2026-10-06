package io.intenttrace.record.adapter.`in`.browser

import io.intenttrace.identity.application.MySessions
import io.intenttrace.identity.application.SessionChannel
import io.intenttrace.identity.domain.ActorIdentity
import io.intenttrace.record.application.ActivityVisibility
import io.intenttrace.record.application.RecordActivities
import io.intenttrace.record.application.RecordOperation
import org.springframework.web.servlet.ModelAndView

internal fun RecordBrowserPage.sessions(actor: ActorIdentity, result: MySessions): ModelAndView =
    view("sessions", "내 연결 관리", actor, SessionsView(result.sessions.map { session ->
        SessionView(if (session.current) "현재 연결" else "다른 연결",
            "${if (session.channel == SessionChannel.BROWSER) "브라우저" else "Agent·API"} 연결 · ${session.id.toString().take(8)}",
            time(session.createdAt), time(session.lastUsedAt), time(session.expiresAt), "/records/sessions/${session.id}/revoke",
            if (session.current) "현재 연결 종료·로그아웃" else "이 연결 종료")
    }))

internal fun RecordBrowserPage.activities(actor: ActorIdentity, result: RecordActivities, searchUrl: String?): ModelAndView =
    view("activities", "기록 변경 이력", actor, ActivitiesView(recordUrl(result.recordId, searchUrl),
        result.visibility == ActivityVisibility.TEAM, result.items.map { activity ->
            ActivityView(activity.version, activity.operation.label, time(activity.occurredAt),
                if (activity.actorSubject == result.author.subject) "@${result.author.login}" else activity.actorSubject,
                "${activity.previousStatus?.let { "${it.label} → " }.orEmpty()}${activity.status.label}")
        }, result.nextBeforeVersion?.let { recordUrl(result.recordId, searchUrl, "activities", "beforeVersion" to it) }))

private val RecordOperation.label: String get() = when (this) {
    RecordOperation.CREATE -> "초안 생성"; RecordOperation.REVISE -> "초안 수정"; RecordOperation.CONFIRM -> "작성자 확인"
    RecordOperation.REOPEN -> "작성자 확인 취소"; RecordOperation.PUBLISH -> "팀 공개"; RecordOperation.DISCARD -> "기록 폐기"
    RecordOperation.SUPERSEDE -> "새 기록으로 대체"
}

data class SessionsView(val sessions: List<SessionView>)

data class SessionView(val state: String, val heading: String, val createdAt: TimeView, val lastUsedAt: TimeView,
    val expiresAt: TimeView, val revokeUrl: String, val action: String)

/** [teamOnly]이면 팀원에게 공개·대체 작업만 보인다는 안내를 표시한다. */
data class ActivitiesView(val backUrl: String, val teamOnly: Boolean, val items: List<ActivityView>, val previousUrl: String?)

data class ActivityView(val version: Long, val operation: String, val occurredAt: TimeView, val actor: String, val transition: String)
