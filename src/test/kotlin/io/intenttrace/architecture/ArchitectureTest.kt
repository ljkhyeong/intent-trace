package io.intenttrace.architecture

import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.intenttrace.architecture.fixture.adapter.`in`.BadController
import io.intenttrace.architecture.fixture.application.BadService
import io.intenttrace.architecture.fixture.cycle.left.application.LeftService
import io.intenttrace.architecture.fixture.cycle.right.domain.RightPolicy
import io.intenttrace.architecture.fixture.domain.BadDomain
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class ArchitectureTest {
    @Test
    fun `Controller와 MCP는 저장소를 직접 호출하지 않는다`() = inboundRule.check(production)

    @Test
    fun `도메인은 애플리케이션과 인프라에 의존하지 않는다`() = domainRule.check(production)

    @Test
    fun `서비스는 저장소 구현체와 어댑터에 의존하지 않는다`() = applicationRule.check(production)

    @Test
    fun `모듈의 서비스와 도메인은 서로 순환 의존하지 않는다`() = moduleCycleRule.check(production)

    @Test
    fun `잘못된 의존을 넣으면 각 규칙이 해당 클래스를 지적한다`() {
        for ((rule, fixtures) in listOf(inboundRule to listOf(BadController::class.java),
            domainRule to listOf(BadDomain::class.java), applicationRule to listOf(BadService::class.java),
            moduleCycleRule to listOf(LeftService::class.java, RightPolicy::class.java))) {
            val error = assertFailsWith<AssertionError> { rule.check(ClassFileImporter().importClasses(fixtures)) }
            fixtures.forEach { assertContains(error.message.orEmpty(), it.simpleName) }
        }
    }

    companion object {
        private val production by lazy {
            ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.intenttrace")
        }
        private val infrastructure = resideInAnyPackage("..adapter.out..", "..infrastructure..",
            "org.springframework.jdbc..", "org.springframework.data.repository..", "java.sql..", "javax.sql..")
        private val repositories = resideInAPackage("..application..").and(simpleNameEndingWith("Repository"))
        private val inboundRule = noClasses().that().resideInAPackage("..adapter.in..")
            .should().dependOnClassesThat(infrastructure.or(repositories))
            .because("Controller와 MCP는 애플리케이션 서비스를 통해 저장소를 사용한다")
        private val domainRule = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat(resideInAnyPackage("..application..", "..adapter..", "..infrastructure..",
                "..config..", "org.springframework..", "java.sql..", "javax.sql..", "jakarta.persistence.."))
            .because("도메인 규칙은 외부 연동과 실행 환경에서 독립적이어야 한다")
        private val applicationRule = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat(infrastructure.or(resideInAPackage("..adapter.in..")))
            .because("서비스는 구현체 대신 애플리케이션 포트를 주입받는다")
        // 여러 모듈을 조합하는 어댑터와 공용 설정은 제외하고 모듈 이름별로 서비스·도메인 계층을 묶는다.
        private val moduleCycleRule = slices().matching("..(*).[application|domain]..").should().beFreeOfCycles()
            .because("모듈 서비스가 서로 의존하면 한 모듈의 변경이 순환을 따라 퍼진다")
    }
}
