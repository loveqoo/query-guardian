package com.loveqoo.queryguardian

import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields

/** spec §5.1: Druid 타입은 parser 패키지 밖으로 새지 않는다 — 파서 교체를 국소 변경으로 유지하는 경계. */
@AnalyzeClasses(packages = ["com.loveqoo.queryguardian"])
class ArchIsolationTest {

    @ArchTest
    val druidStaysInParserPackage: ArchRule = noClasses()
        .that().resideOutsideOfPackage("..queryguardian.parser..")
        .should().dependOnClassesThat().resideInAnyPackage("com.alibaba.druid..")

    /**
     * spec 007 §6.4 (C4): 판정 경로는 권한·세션을 알지 못한다.
     * 판정 카탈로그에 권한이 새면 권한 없는 사용자에게 파티션·필터·BLOCK 룰이 한 건도 발화하지 않는
     * 역전(권한이 없을수록 룰을 덜 받음)이 생겨 spec 001 §6 fail-closed 계약이 파괴된다.
     */
    @ArchTest
    val judgmentPathKnowsNothingAboutAuth: ArchRule = noClasses()
        .that().resideInAnyPackage("..queryguardian.rules..", "..queryguardian.lint..", "..queryguardian.ir..")
        .should().dependOnClassesThat().resideInAnyPackage("..queryguardian.auth..")

    /**
     * spec 008 §3.5 M1-1: `ir`은 **공용 어휘**다 — 아무 계층도 의존하지 않는다.
     * `RewritePlan`이 여기 사는 이유가 그것이고(`exec`에 두면 `parser`가 `exec`를 의존해야 한다),
     * 어휘가 어느 계층을 알기 시작하면 그 방향 의존이 순환으로 되돌아온다.
     */
    @ArchTest
    val irIsTheSharedVocabulary: ArchRule = noClasses()
        .that().resideInAnyPackage("..queryguardian.ir..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..queryguardian.parser..", "..queryguardian.catalog..", "..queryguardian.rules..",
            "..queryguardian.auth..", "..queryguardian.exec..", "..queryguardian.approval..",
            "..queryguardian.api..", "..queryguardian.lint..", "..queryguardian.query..",
            "..queryguardian.config..", "..queryguardian.audit..",
        )

    /**
     * spec 010 I13·P0: `audit`은 **결말의 공용 어휘**다 — `ir`이 SQL의 공용 어휘인 것과 같은 자리다.
     * 게이트·권한·승인·실행이 모두 이 어휘를 말해야 하므로, 이것이 어느 한 계층을 의존하기 시작하면
     * 그 방향이 곧 순환으로 되돌아온다. 어휘는 아무 것도 몰라야 모두가 쓸 수 있다.
     */
    @ArchTest
    val auditVocabularyDependsOnNothing: ArchRule = noClasses()
        .that().resideInAnyPackage("..queryguardian.audit..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..queryguardian.parser..", "..queryguardian.catalog..", "..queryguardian.rules..",
            "..queryguardian.auth..", "..queryguardian.exec..", "..queryguardian.approval..",
            "..queryguardian.api..", "..queryguardian.lint..", "..queryguardian.query..",
            "..queryguardian.config..", "..queryguardian.ir..",
        )

    /**
     * spec 008 §3: 재작성이 권한에 따라 달라지면 "권한 없는 사용자가 마스킹을 덜 받는" 역전이 생긴다
     * (spec 007 C4와 같은 함정). 실행·재작성 계층은 권한을 알지 못한다 — 권한 판단은 호출자(게이트)의 몫이다.
     */
    @ArchTest
    val execKnowsNothingAboutAuth: ArchRule = noClasses()
        .that().resideInAnyPackage("..queryguardian.exec..")
        .should().dependOnClassesThat().resideInAnyPackage("..queryguardian.auth..")

    /**
     * spec 010 **I9·A6**: 실행 허가증([Executable])은 **어디에도 저장되지 않는다.**
     *
     * 스펙은 단회성 토큰을 요구했는데 만들지 않았다 — 재사용이 **구조로** 성립하지 않기 때문이다.
     * 그 근거가 바로 이것이다: 증거가 필드에 담기지 않으면 참조는 `execute`의 스택에서만 살고,
     * 호출이 끝나면 사라진다. 소비 플래그는 "보관될 수 있다"를 전제할 때 필요한 장치다.
     *
     * **이 규칙이 깨지는 날이 토큰이 필요해지는 날이다** — 게이트 통과와 실행이 다른 호출로 갈라지면
     * 누군가 증거를 들고 있어야 하고, 그 순간 여기서 먼저 실패한다.
     */
    @ArchTest
    val executionEvidenceIsNeverStored: ArchRule = noFields()
        .should().haveRawType("com.loveqoo.queryguardian.query.Executable")
        .because("실행 허가증이 필드에 담기면 그 참조를 다시 쓸 수 있다 — 단회성은 '보관되지 않음'에서 나온다")

    /**
     * spec 010 I2: 단계 증거는 **게이트 밖으로 나가지 않는다.**
     *
     * 밖에서 증거 타입을 아는 순간 그것을 파라미터로 받는 함수가 생길 수 있고, 그러면 증거가
     * 게이트 밖 어딘가에 담긴다. 컨트롤러·서비스가 아는 것은 게이트의 **결과**(`ExecutedQuery`·
     * `PreviewedRewrite`)이지 그 과정의 증거가 아니다.
     *
     * **이름을 문자열로 적는 이유**: 클래스 리터럴을 쓰면 이 테스트 클래스 자신이 그 타입을 의존하게 되어
     * 규칙이 스스로를 위반한다(실측). 이름이 어긋나 규칙이 공허해지는 것은 `GateEvidenceClosureTest`의
     * `단계 목록이 ArchUnit 규칙과 같은 이름을 가리킨다`가 막는다.
     */
    @ArchTest
    val gateEvidenceStaysInTheGate: ArchRule = noClasses()
        .that().resideOutsideOfPackage("..queryguardian.query..")
        .should().dependOnClassesThat().haveNameMatching(STAGE_TYPES.joinToString("|") { Regex.escape(it) })

    companion object {
        /** 게이트 단계 증거 타입의 FQN — `GateEvidenceClosureTest`가 이 목록과 실제 타입을 대조한다. */
        val STAGE_TYPES = listOf(
            "Parsed", "Authorized", "Covered", "Judged", "Storable", "Mapped", "Planned", "Ready", "Executable",
        ).map { "com.loveqoo.queryguardian.query.$it" }
    }

    /**
     * spec 008 §3: `parser`는 방언 중립 계획(RewritePlan)만 받아 AST를 조작한다 — 카탈로그·권한·규칙 미인지.
     * 이 경계가 깨지면 물리명으로 제약을 조회해 **마스킹이 조용히 0건 적용**되는 함정이 파서 안으로 들어온다.
     */
    @ArchTest
    val parserKnowsOnlyIr: ArchRule = noClasses()
        .that().resideInAnyPackage("..queryguardian.parser..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..queryguardian.catalog..", "..queryguardian.rules..", "..queryguardian.auth..",
            "..queryguardian.exec..", "..queryguardian.approval..",
        )
}
