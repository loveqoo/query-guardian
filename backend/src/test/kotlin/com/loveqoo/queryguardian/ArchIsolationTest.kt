package com.loveqoo.queryguardian

import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

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
            "..queryguardian.config..",
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
