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
}
