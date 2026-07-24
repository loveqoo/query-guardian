package com.loveqoo.queryguardian.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.loveqoo.queryguardian.catalog.CatalogTableRepository
import com.loveqoo.queryguardian.catalog.ConstraintDefRepository
import com.loveqoo.queryguardian.catalog.ConstraintMappingRepository
import com.loveqoo.queryguardian.catalog.DbTableCatalog
import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.parser.DruidMySqlParser
import com.loveqoo.queryguardian.rules.RuleEngine
import com.loveqoo.queryguardian.rules.TableCatalog
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class GuardianConfig {

    @Bean
    fun dialectParser(): DialectParser = DruidMySqlParser()

    @Bean
    fun ruleEngine(@Value("\${guardian.limit.max:1000}") maxLimit: Long): RuleEngine =
        RuleEngine.withDefaultRules(maxLimit)

    @Bean
    fun tableCatalog(
        parser: DialectParser,
        tables: CatalogTableRepository,
        defs: ConstraintDefRepository,
        mappings: ConstraintMappingRepository,
        objectMapper: ObjectMapper,
    ): TableCatalog = DbTableCatalog(parser, tables, defs, mappings, objectMapper)

    @Bean
    fun lintService(parser: DialectParser, engine: RuleEngine, catalog: TableCatalog): LintService =
        LintService(parser, engine, catalog)
}
