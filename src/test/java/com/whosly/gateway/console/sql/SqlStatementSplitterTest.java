package com.whosly.gateway.console.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlStatementSplitterTest {

    @Test
    void splitsOnSemicolon() {
        List<String> parts = SqlStatementSplitter.split("SELECT 1; SELECT 2;");
        assertThat(parts).containsExactly("SELECT 1", "SELECT 2");
    }

    @Test
    void ignoresSemicolonInQuotes() {
        List<String> parts = SqlStatementSplitter.split("SELECT 'a;b'; SELECT 2");
        assertThat(parts).containsExactly("SELECT 'a;b'", "SELECT 2");
    }

    @Test
    void ignoresSemicolonInLineComment() {
        List<String> parts = SqlStatementSplitter.split("SELECT 1; -- note; ignored\nSELECT 2");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).isEqualTo("SELECT 1");
        // line comment after ; attaches to the next statement segment
        assertThat(parts.get(1)).contains("SELECT 2");
        assertThat(parts.get(1)).contains("-- note; ignored");
    }

    @Test
    void ignoresSemicolonInBlockComment() {
        List<String> parts = SqlStatementSplitter.split("SELECT 1; /* a;b */ SELECT 2");
        assertThat(parts).containsExactly("SELECT 1", "/* a;b */ SELECT 2");
    }

    @Test
    void enforcesMaxStatements() {
        assertThatThrownBy(() -> SqlStatementSplitter.split("SELECT 1; SELECT 2; SELECT 3", 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多支持");
    }

    @Test
    void dropsEmptySegments() {
        assertThat(SqlStatementSplitter.split("SELECT 1;;;")).containsExactly("SELECT 1");
    }
}
