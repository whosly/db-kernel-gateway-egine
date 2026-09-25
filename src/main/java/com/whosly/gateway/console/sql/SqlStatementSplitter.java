package com.whosly.gateway.console.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQL script on {@code ;} while respecting single/double quotes,
 * MySQL-style backticks, line comments ({@code --} / {@code #}), and
 * C-style block comments. Empty segments are dropped.
 */
public final class SqlStatementSplitter {

    public static final int DEFAULT_MAX_STATEMENTS = 20;

    private SqlStatementSplitter() {
    }

    public static List<String> split(String sql) {
        return split(sql, DEFAULT_MAX_STATEMENTS);
    }

    /**
     * @throws IllegalArgumentException if more than {@code maxStatements} non-empty statements
     */
    public static List<String> split(String sql, int maxStatements) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        if (maxStatements < 1) {
            throw new IllegalArgumentException("maxStatements must be >= 1");
        }
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        final int n = sql.length();
        char quote = 0; // 0 = not in quote; else ', ", or `
        boolean lineComment = false;
        boolean blockComment = false;

        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : 0;

            if (lineComment) {
                current.append(c);
                if (c == '\n') {
                    lineComment = false;
                }
                i++;
                continue;
            }
            if (blockComment) {
                current.append(c);
                if (c == '*' && next == '/') {
                    current.append(next);
                    i += 2;
                    blockComment = false;
                    continue;
                }
                i++;
                continue;
            }
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && next != 0 && (quote == '\'' || quote == '"')) {
                    // escape next char inside ' or " (common SQL dialects)
                    current.append(next);
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    // doubled quote escape: '' or "" or ``
                    if (next == quote) {
                        current.append(next);
                        i += 2;
                        continue;
                    }
                    quote = 0;
                }
                i++;
                continue;
            }

            // not in quote / comment
            if (c == '-' && next == '-') {
                current.append(c).append(next);
                i += 2;
                lineComment = true;
                continue;
            }
            if (c == '#') {
                current.append(c);
                i++;
                lineComment = true;
                continue;
            }
            if (c == '/' && next == '*') {
                current.append(c).append(next);
                i += 2;
                blockComment = true;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                current.append(c);
                i++;
                continue;
            }
            if (c == ';') {
                pushStatement(out, current, maxStatements);
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        pushStatement(out, current, maxStatements);
        return List.copyOf(out);
    }

    private static void pushStatement(List<String> out, StringBuilder current, int maxStatements) {
        String s = current.toString().trim();
        if (s.isEmpty()) {
            return;
        }
        if (out.size() >= maxStatements) {
            throw new IllegalArgumentException(
                    "最多支持 " + maxStatements + " 条语句（以 ; 分隔）；请拆分后再执行");
        }
        out.add(s);
    }
}
