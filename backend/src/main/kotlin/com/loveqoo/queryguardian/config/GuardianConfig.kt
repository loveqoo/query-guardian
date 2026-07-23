package com.loveqoo.queryguardian.config

import com.loveqoo.queryguardian.catalog.CatalogTableRepository
import com.loveqoo.queryguardian.catalog.DbTableCatalog
import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.parser.DruidMySqlParser
import com.loveqoo.queryguardian.rules.RuleEngine
import com.loveqoo.queryguardian.rules.TableCatalog
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class GuardianConfig {

    @Bean
    fun dialectParser(): DialectParser = DruidMySqlParser()

    @Bean
    fun ruleEngine(): RuleEngine = RuleEngine.withDefaultRules()

    @Bean
    fun tableCatalog(parser: DialectParser, repository: CatalogTableRepository): TableCatalog =
        DbTableCatalog(parser, repository)

    @Bean
    fun lintService(parser: DialectParser, engine: RuleEngine, catalog: TableCatalog): LintService =
        LintService(parser, engine, catalog)
}
