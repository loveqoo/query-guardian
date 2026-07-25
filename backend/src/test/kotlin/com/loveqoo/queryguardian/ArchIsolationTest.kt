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
}
