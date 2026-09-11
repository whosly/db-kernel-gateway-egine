package com.whosly.gateway.parser;

import com.alibaba.druid.sql.ast.SQLStatement;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classifies observed SQL into {@link StatementEffect}s.
 *
 * <p>Classification runs on every observed statement, so the common path only
 * scans the leading keyword and a couple of patterns instead of building an AST.
 * The optional {@link SqlParser} is consulted for the one case a keyword cannot
 * answer: a {@code WITH} prefix, where the statement may end in a read or a
 * write.</p>
 *
 * <p>The classifier is deliberately conservative. Anything it cannot classify is
 * reported as {@link StatementEffect#UNKNOWN}, which marks the session dirty
 * rather than risk handing session state to another client.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class StatementClassifier {

    private static final Pattern USER_VARIABLE_REFERENCE = Pattern.compile("@[A-Za-z_]");
    private static final Pattern TEMPORARY_KEYWORD = Pattern.compile("(?i)\\bTEMP(?:ORARY)?\\b");
    private static final Pattern LOCK_FUNCTION =
            Pattern.compile("(?i)\\b(?:pg_advisory_lock|pg_advisory_xact_lock|get_lock|release_lock)\\s*\\(");

    private final SqlParser sqlParser;

    /**
     * @param sqlParser optional parser used only to classify {@code WITH}
     *                  statements; may be {@code null}
     */
    public StatementClassifier(SqlParser sqlParser) {
        this.sqlParser = sqlParser;
    }

    /**
     * Classifies one observed statement.
     *
     * @return the effects observed; never empty
     */
    public Set<StatementEffect> classify(String sql) {
        if (sql == null || sql.isBlank()) {
            return Set.of(StatementEffect.UNKNOWN);
        }

        String statement = stripLeadingNoise(sql);
        Set<StatementEffect> effects = EnumSet.noneOf(StatementEffect.class);

        if (containsMultipleStatements(statement)) {
            // A batch cannot be classified as one statement, so treat it as opaque.
            effects.add(StatementEffect.UNKNOWN);
        }
        if (USER_VARIABLE_REFERENCE.matcher(statement).find()) {
            effects.add(StatementEffect.USER_VARIABLE);
        }
        if (LOCK_FUNCTION.matcher(statement).find()) {
            effects.add(StatementEffect.LOCK);
        }

        switch (leadingKeyword(statement)) {
            case "SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "VALUES", "TABLE", "FETCH",
                 "ANALYZE", "VACUUM" -> effects.add(StatementEffect.READ);
            case "INSERT", "UPDATE", "DELETE", "REPLACE", "MERGE", "TRUNCATE", "LOAD", "COPY",
                 "IMPORT" -> effects.add(StatementEffect.WRITE);
            case "CREATE", "ALTER", "DROP", "RENAME", "COMMENT", "GRANT", "REVOKE" -> {
                effects.add(StatementEffect.DDL);
                if (TEMPORARY_KEYWORD.matcher(statement).find()) {
                    effects.add(StatementEffect.TEMPORARY_OBJECT);
                }
            }
            case "BEGIN", "START", "COMMIT", "ROLLBACK", "END", "SAVEPOINT", "RELEASE", "XA" ->
                    effects.add(StatementEffect.TRANSACTION_CONTROL);
            case "SET", "RESET", "DISCARD", "USE", "LISTEN", "UNLISTEN" ->
                    effects.add(StatementEffect.SESSION_SETTING);
            case "LOCK", "UNLOCK", "GET_LOCK", "RELEASE_LOCK" -> effects.add(StatementEffect.LOCK);
            case "WITH" -> effects.addAll(classifyCommonTableExpression(sql));
            default -> effects.add(StatementEffect.UNKNOWN);
        }

        if (effects.isEmpty()) {
            effects.add(StatementEffect.UNKNOWN);
        }
        return effects;
    }

    /**
     * Classifies a {@code WITH} statement through the parser, because the CTE may
     * end in a read or in a write.
     */
    private Set<StatementEffect> classifyCommonTableExpression(String sql) {
        if (sqlParser == null) {
            return Set.of(StatementEffect.UNKNOWN);
        }
        try {
            SQLStatement statement = sqlParser.parse(sql);
            String kind = statement.getClass().getSimpleName();
            if (kind.contains("Insert") || kind.contains("Update") || kind.contains("Delete")
                    || kind.contains("Replace") || kind.contains("Merge")) {
                return Set.of(StatementEffect.WRITE);
            }
            if (kind.contains("Select")) {
                return Set.of(StatementEffect.READ);
            }
        } catch (SqlParseException e) {
            // An unparsable statement cannot be classified; fall through to UNKNOWN.
        }
        return Set.of(StatementEffect.UNKNOWN);
    }

    /**
     * Removes leading whitespace, comments and parentheses so the first keyword is
     * the statement verb.
     */
    private static String stripLeadingNoise(String sql) {
        int cursor = 0;
        int length = sql.length();
        while (cursor < length) {
            char current = sql.charAt(cursor);
            if (Character.isWhitespace(current) || current == '(') {
                cursor++;
                continue;
            }
            if (current == '/' && cursor + 1 < length && sql.charAt(cursor + 1) == '*') {
                int commentEnd = sql.indexOf("*/", cursor + 2);
                if (commentEnd < 0) {
                    break;
                }
                cursor = commentEnd + 2;
                continue;
            }
            if (current == '-' && cursor + 1 < length && sql.charAt(cursor + 1) == '-') {
                int commentEnd = sql.indexOf('\n', cursor + 2);
                if (commentEnd < 0) {
                    break;
                }
                cursor = commentEnd + 1;
                continue;
            }
            break;
        }
        return sql.substring(cursor).trim();
    }

    private static String leadingKeyword(String statement) {
        int end = 0;
        while (end < statement.length()
                && (Character.isLetterOrDigit(statement.charAt(end)) || statement.charAt(end) == '_')) {
            end++;
        }
        return statement.substring(0, end).toUpperCase(Locale.ROOT);
    }

    /**
     * True when another statement follows a semicolon, which makes the batch
     * unclassifiable as a whole.
     */
    private static boolean containsMultipleStatements(String statement) {
        int lastSemicolon = lastSemicolonOutsideQuotes(statement);
        return lastSemicolon >= 0 && !statement.substring(lastSemicolon + 1).isBlank();
    }

    private static int lastSemicolonOutsideQuotes(String statement) {
        int lastSemicolon = -1;
        char quote = 0;
        for (int index = 0; index < statement.length(); index++) {
            char current = statement.charAt(index);
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                continue;
            }
            if (current == ';') {
                lastSemicolon = index;
            }
        }
        return lastSemicolon;
    }
}
