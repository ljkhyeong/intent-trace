import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "io.intenttrace.intellij"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val localIdePath = providers.gradleProperty("localIdePath").orNull

dependencies {
    intellijPlatform {
        if (localIdePath == null) {
            intellijIdea("2025.3.2")
        } else {
            local(localIdePath)
        }
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test-junit"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        id = "io.intenttrace.lineintent"
        name = "IntentTrace"
        version = project.version.toString()
        description = """
            IntentTrace displays change intent, personal drafts and file history with links to original commits and verification evidence.
            현재 줄의 변경 의도, 팀 공개 기록과 내 비공개 기록을 조회하고 원래 커밋·코드 근거·검증을 확인합니다.
        """.trimIndent()
        ideaVersion {
            sinceBuild = "253"
        }
        vendor {
            name = "IntentTrace contributors"
            url = "https://github.com/ljkhyeong/intent-trace"
        }
    }
}

tasks.test {
    maxHeapSize = "1g"
}
