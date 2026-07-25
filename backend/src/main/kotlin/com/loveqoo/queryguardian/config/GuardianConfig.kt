package com.loveqoo.queryguardian.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.loveqoo.queryguardian.catalog.ConstraintDefRepository
import com.loveqoo.queryguardian.catalog.ConstraintMappingRepository
import com.loveqoo.queryguardian.catalog.DbTableCatalog
import com.loveqoo.queryguardian.exec.RewriteCatalog
import com.loveqoo.queryguardian.exec.RewritePlanner
import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.parser.DruidMySqlParser
import com.loveqoo.queryguardian.rules.RuleEngine
import com.loveqoo.queryguardian.rules.TableCatalog
import com.loveqoo.queryguardian.rules.UserRuleEvaluator
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class GuardianConfig {

    @Bean
    fun dialectParser(): DialectParser = DruidMySqlParser()

    @Bean
    fun userRuleEvaluator(ruleService: com.loveqoo.queryguardian.rules.RuleService): UserRuleEvaluator =
        UserRuleEvaluator { ruleService.activeUserRules() }

    @Bean
    fun ruleEngine(
        @Value("\${guardian.limit.max:1000}") maxLimit: Long,
        userRuleEvaluator: UserRuleEvaluator,
    ): RuleEngine = RuleEngine.withDefaultRules(maxLimit, userRuleEvaluator)

    @Bean
    fun tableCatalog(
        parser: DialectParser,
        bindings: com.loveqoo.queryguardian.catalog.ConstraintBindingReader,
        defs: ConstraintDefRepository,
        mappings: ConstraintMappingRepository,
        objectMapper: ObjectMapper,
    ): TableCatalog = DbTableCatalog(parser, bindings, defs, mappings, objectMapper)

    /**
     * 재작성 계획 수립기 (spec 008 M1-3). 상한은 설정값 하나로 관리한다 —
     * 재작성이 넣는 LIMIT과 실행기의 상한이 갈라지면 `truncated` 판정이 어긋난다(§3.0-2 단일 장치).
     */
    @Bean
    fun rewritePlanner(
        @Value("\${guardian.exec.max-rows:1000}") maxRows: Long,
        rewriteCatalog: RewriteCatalog,
    ): RewritePlanner = RewritePlanner(rewriteCatalog, maxRows)

    @Bean
    fun lintService(parser: DialectParser, engine: RuleEngine, catalog: TableCatalog): LintService =
        LintService(parser, engine, catalog)
}
