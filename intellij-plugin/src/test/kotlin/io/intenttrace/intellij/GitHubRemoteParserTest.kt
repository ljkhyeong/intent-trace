package io.intenttrace.intellij

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubRemoteParserTest {
    @Test
    fun `GitHub HTTPS remote을 저장소 키로 변환한다`() {
        assertEquals(
            "ljkhyeong/intent-trace",
            GitHubRemoteParser.repositoryKey("https://github.com/LJKhyeong/Intent-Trace.GIT"),
        )
    }

    @Test
    fun `GitHub SSH remote을 저장소 키로 변환한다`() {
        assertEquals(
            "ljkhyeong/intent-trace",
            GitHubRemoteParser.repositoryKey("git@github.com:LJKhyeong/Intent-Trace.git"),
        )
        assertEquals(
            "ljkhyeong/intent-trace",
            GitHubRemoteParser.repositoryKey("ssh://git@github.com/LJKhyeong/Intent-Trace.git"),
        )
    }

    @Test
    fun `GitHub가 아닌 remote은 사용하지 않는다`() {
        assertNull(GitHubRemoteParser.repositoryKey("git@gitlab.com:team/intent-trace.git"))
    }
}
