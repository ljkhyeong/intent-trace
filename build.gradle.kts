plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "io.intenttrace"
version = "0.12.3-SNAPSHOT"
description = "Intent-aware change provenance for AI-assisted development"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
	implementation("org.springframework.boot:spring-boot-h2console")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-restclient")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.security:spring-security-oauth2-jose")
	implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	runtimeOnly("com.h2database:h2")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("com.tngtech.archunit:archunit:1.5.0")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll(
			"-Xjsr305=strict",
			"-Xannotation-default-target=param-property",
			"-Xemit-jvm-type-annotations",
		)
	}
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

val postgresTestClass = "**/PostgresRepositorySmokeTest.class"
val architectureTestClass = "**/ArchitectureTest.class"

val architectureTest by tasks.registering(Test::class) {
	description = "Controller·MCP, application, domain의 의존 규칙을 검사합니다."
	group = "verification"
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	include(architectureTestClass)
}

tasks.test {
	dependsOn(architectureTest)
	exclude(postgresTestClass)
	exclude(architectureTestClass)
}

tasks.register<Test>("focusedTest") {
	description = "--tests로 지정한 관련 테스트를 실행하고 전체 테스트 결과를 보존합니다."
	group = "verification"
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	exclude(postgresTestClass)
}

tasks.withType<Test>().matching { it.name != "postgresTest" }.configureEach {
	inputs.files("scripts/git-evidence.sh", "scripts/run-verification.py")
	inputs.files(fileTree("clients/zed") { include("*.mjs", "package.json", "package-lock.json") })
	inputs.property("zedSdkInstalled", providers.provider {
		file("clients/zed/node_modules/@modelcontextprotocol/sdk/package.json").isFile
	})
}

tasks.register<Test>("postgresTest") {
	description = "별도 PostgreSQL에서 DB 계약을 검증합니다. scripts/verify-postgres.sh로 실행하세요."
	group = "verification"
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	include(postgresTestClass)
	// 검증 스크립트가 매번 새 DB를 만들므로 이전 실행 결과를 재사용하지 않는다.
	outputs.upToDateWhen { false }
	outputs.doNotCacheIf("새 PostgreSQL에 테스트 데이터를 다시 생성해야 합니다.") { true }
	doFirst {
		check(System.getenv("INTENT_TRACE_POSTGRES_SMOKE") == "true") {
			"PostgreSQL 검증은 scripts/verify-postgres.sh로 실행하세요."
		}
	}
}

tasks.bootJar {
	archiveFileName.set("intent-trace.jar")
}
