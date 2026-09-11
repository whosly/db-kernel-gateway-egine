package com.whosly.gateway.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlMaskerTest {

    private final SqlMasker masker = SqlMasker.standard();

    @Test
    void masksStringAndNumericLiterals() {
        assertThat(masker.mask("select * from account where id = 42 and name = 'alice'"))
                .isEqualTo("select * from account where id = ? and name = ?");
        assertThat(masker.mask("insert into t values ('a', 1.5, -2, 1e3)"))
                .isEqualTo("insert into t values (?, ?, -?, ?)");
    }

    @Test
    void keepsIdentifiersPlaceholdersAndQuotedNames() {
        assertThat(masker.mask("select col1, t2.c3 from table1 where id = $1"))
                .isEqualTo("select col1, t2.c3 from table1 where id = $1");
        assertThat(masker.mask("select `col1` from `2024_data`"))
                .isEqualTo("select `col1` from `2024_data`");
    }

    @Test
    void masksEscapedAndDoubledQuotesAsOneLiteral() {
        assertThat(masker.mask("select 'it''s', 'a\\'b'"))
                .isEqualTo("select ?, ?");
    }

    @Test
    void keepsCommentsSoTheStatementShapeSurvives() {
        assertThat(masker.mask("select 1 -- keep 42\nfrom t /* keep 7 */ where id = 9"))
                .isEqualTo("select ? -- keep 42\nfrom t /* keep 7 */ where id = ?");
    }

    @Test
    void masksAnUnterminatedLiteralInsteadOfEchoingIt() {
        assertThat(masker.mask("select 'secret")).isEqualTo("select ?");
    }

    @Test
    void returnsInputForNullOrEmptyText() {
        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEmpty();
    }
}
