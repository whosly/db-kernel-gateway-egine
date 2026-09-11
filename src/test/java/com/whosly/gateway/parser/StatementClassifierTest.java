package com.whosly.gateway.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatementClassifierTest {

    private final StatementClassifier classifier = new StatementClassifier(new DruidSqlParser());

    @Test
    void classifiesDataAccessStatements() {
        assertThat(classifier.classify("select * from account")).containsExactly(StatementEffect.READ);
        assertThat(classifier.classify("  /* hint */ SHOW TABLES")).containsExactly(StatementEffect.READ);
        assertThat(classifier.classify("insert into account values (1)")).containsExactly(StatementEffect.WRITE);
        assertThat(classifier.classify("delete from account")).containsExactly(StatementEffect.WRITE);
    }

    @Test
    void classifiesSchemaAndTransactionStatements() {
        assertThat(classifier.classify("create table t (id int)")).containsExactly(StatementEffect.DDL);
        assertThat(classifier.classify("create temporary table t (id int)"))
                .containsExactlyInAnyOrder(StatementEffect.DDL, StatementEffect.TEMPORARY_OBJECT);
        assertThat(classifier.classify("begin")).containsExactly(StatementEffect.TRANSACTION_CONTROL);
        assertThat(classifier.classify("rollback")).containsExactly(StatementEffect.TRANSACTION_CONTROL);
    }

    @Test
    void classifiesSessionScopedState() {
        assertThat(classifier.classify("set names utf8mb4")).containsExactly(StatementEffect.SESSION_SETTING);
        assertThat(classifier.classify("set @total = 1"))
                .containsExactlyInAnyOrder(StatementEffect.SESSION_SETTING, StatementEffect.USER_VARIABLE);
        assertThat(classifier.classify("select @total"))
                .containsExactlyInAnyOrder(StatementEffect.READ, StatementEffect.USER_VARIABLE);
        assertThat(classifier.classify("lock tables account write")).containsExactly(StatementEffect.LOCK);
        assertThat(classifier.classify("select pg_advisory_lock(42)"))
                .containsExactlyInAnyOrder(StatementEffect.READ, StatementEffect.LOCK);
    }

    @Test
    void treatsUnclassifiableStatementsAsUnknown() {
        assertThat(classifier.classify("call refresh_stats()")).containsExactly(StatementEffect.UNKNOWN);
        assertThat(classifier.classify("select 1; select 2"))
                .containsExactlyInAnyOrder(StatementEffect.READ, StatementEffect.UNKNOWN);
        assertThat(classifier.classify("   ")).containsExactly(StatementEffect.UNKNOWN);
        assertThat(classifier.classify(null)).containsExactly(StatementEffect.UNKNOWN);
    }

    @Test
    void usesTheParserForCommonTableExpressions() {
        assertThat(classifier.classify("with recent as (select 1) select * from recent"))
                .containsExactly(StatementEffect.READ);

        // Without a parser a WITH statement cannot be classified, so it must stay
        // unknown rather than be guessed.
        StatementClassifier keywordOnlyClassifier = new StatementClassifier(null);
        assertThat(keywordOnlyClassifier.classify("with recent as (select 1) select * from recent"))
                .containsExactly(StatementEffect.UNKNOWN);
    }

    @Test
    void ignoresSemicolonsInsideStringLiterals() {
        assertThat(classifier.classify("select 'a;b' from t")).containsExactly(StatementEffect.READ);
    }
}
