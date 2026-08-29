import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.2.20"
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
        description = "IntentTrace shows the published change intent, decisions, and verification evidence for the current committed line."
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
