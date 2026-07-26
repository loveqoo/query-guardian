package com.loveqoo.queryguardian.tools

import com.loveqoo.queryguardian.ir.toAsciiTree
import com.loveqoo.queryguardian.parser.DruidMySqlParser
import com.loveqoo.queryguardian.parser.ParseResult

/**
 * SQL 하나를 파싱해 IR을 아스키 트리로 출력한다.
 *
 *     ./gradlew -q irTree -Psql="SELECT u.id FROM users u WHERE u.id > 10"
 *
 * **테스트 소스에 둔다** — 진단 도구가 프로덕션 jar에 실릴 이유가 없고, Druid를 직접 쓰므로
 * `parser` 봉인(ArchUnit)을 존중하려면 프로덕션 계층에 새 진입점을 만들지 않는 편이 낫다.
 */
fun main(args: Array<String>) {
    val sql = args.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: error("SQL이 필요합니다: ./gradlew -q irTree -Psql=\"SELECT ...\"")

    val inspected = DruidMySqlParser().inspect(sql)

    // 접수 위반은 IR에 남지 않는다(주석·문형 등) — 트리만 보면 놓치므로 함께 찍는다.
    if (inspected.intakeViolations.isNotEmpty()) {
        println("접수 위반 ${inspected.intakeViolations.size}건")
        inspected.intakeViolations.forEach { println("  ! ${it.code}: ${it.message}") }
        println()
    }

    when (val parsed = inspected.parse) {
        is ParseResult.Success -> println(parsed.ir.toAsciiTree())
        is ParseResult.Failure -> println("파싱 실패 [${parsed.kind}] ${parsed.message}")
    }
}
