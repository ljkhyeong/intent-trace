package io.intenttrace.identity.adapter.`in`.web

import io.intenttrace.identity.application.MySessionService
import io.intenttrace.identity.application.MySessions
import io.intenttrace.identity.application.SessionRevocation
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/me/sessions")
class MySessionController(private val sessions: MySessionService) {
    @GetMapping
    fun list(): ResponseEntity<MySessions> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.list())

    @DeleteMapping("/current")
    fun revokeCurrent(): SessionRevocation = sessions.revoke()

    @DeleteMapping("/{sessionId}")
    fun revoke(@PathVariable sessionId: UUID): SessionRevocation = sessions.revoke(sessionId)

    @DeleteMapping
    fun revokeAll(): SessionRevocation = sessions.revokeAll()
}
