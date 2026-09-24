package com.whosly.gateway.parser;

/**
 * Replaces literal values in SQL text with a placeholder.
 *
 * <p>Audit stores and risk dashboards must not expose the values a client sent,
 * but they still need the statement structure. This masker keeps identifiers,
 * keywords, operators and comments, and replaces:</p>
 * <ul>
 *   <li>single-quoted string literals, including {@code ''} and backslash
 *       escapes, with {@code ?};</li>
 *   <li>numeric literals that appear in a value position, with {@code ?}.</li>
 * </ul>
 *
 * <p>Digits inside identifiers ({@code col1}), server-side placeholders
 * ({@code $1}), bind markers and comments are left alone, so the masked text
 * keeps its shape. Dollar-quoted strings are not recognized: only single-quoted
 * literals and value-position numbers are masked, which is the conservative
 * direction for anything the masker does not model.</p>
 *
 * <p>Masking never touches the wire: it applies to text handed to audit, and the
 * bytes forwarded to the database stay exactly as the client sent them
 * (rule 2.10).</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class SqlMasker {

    /** Text that replaces a masked literal. */
    public static final String PLACEHOLDER = "?";

    private static final SqlMasker STANDARD = new SqlMasker();

    /** The shared, stateless masker. */
    public static SqlMasker standard() {
        return STANDARD;
    }

    /**
     * Masks literal values in one statement.
     *
     * @return the masked text; {@code null} in, {@code null} out
     */
    public String mask(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }

        StringBuilder masked = new StringBuilder(sql.length());
        int index = 0;
        int length = sql.length();
        while (index < length) {
            char current = sql.charAt(index);

            if (current == '\'') {
                index = maskQuotedLiteral(sql, index, masked);
                continue;
            }
            if (isLineCommentStart(sql, index)) {
                int end = sql.indexOf('\n', index);
                int stop = end < 0 ? length : end + 1;
                masked.append(sql, index, stop);
                index = stop;
                continue;
            }
            if (isBlockCommentStart(sql, index)) {
                int end = sql.indexOf("*/", index + 2);
                int stop = end < 0 ? length : end + 2;
                masked.append(sql, index, stop);
                index = stop;
                continue;
            }
            if (Character.isDigit(current) && startsLiteral(sql, index)) {
                masked.append(PLACEHOLDER);
                index = skipNumericLiteral(sql, index);
                continue;
            }

            masked.append(current);
            index++;
        }
        return masked.toString();
    }

    /** Masks a single-quoted literal and returns the offset after it. */
    private static int maskQuotedLiteral(String sql, int start, StringBuilder masked) {
        masked.append(PLACEHOLDER);
        int index = start + 1;
        int length = sql.length();
        while (index < length) {
            char current = sql.charAt(index);
            if (current == '\\') {
                // MySQL backslash escape inside a string literal.
                index += 2;
                continue;
            }
            if (current == '\'') {
                if (index + 1 < length && sql.charAt(index + 1) == '\'') {
                    // Doubled quote: still the same literal.
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        // Unterminated literal: the rest of the text is masked, never echoed back.
        return length;
    }

    /**
     * True when the digit at {@code index} begins a value rather than continuing
     * an identifier, a qualified name or a server-side placeholder.
     */
    private static boolean startsLiteral(String sql, int index) {
        if (index == 0) {
            return true;
        }
        char previous = sql.charAt(index - 1);
        if (Character.isLetterOrDigit(previous) || previous == '_' || previous == '$'
                || previous == '.' || previous == '`' || previous == '"') {
            return false;
        }
        return true;
    }

    /** Consumes a numeric literal, including fraction and exponent. */
    private static int skipNumericLiteral(String sql, int start) {
        int index = start;
        int length = sql.length();
        while (index < length) {
            char current = sql.charAt(index);
            if (Character.isDigit(current) || current == '.') {
                index++;
                continue;
            }
            if (isExponentStart(sql, index)) {
                index += 2;
                continue;
            }
            break;
        }
        return index;
    }

    private static boolean isExponentStart(String sql, int index) {
        char current = sql.charAt(index);
        if (current != 'e' && current != 'E') {
            return false;
        }
        int next = index + 1;
        if (next < sql.length() && Character.isDigit(sql.charAt(next))) {
            return true;
        }
        char sign = next < sql.length() ? sql.charAt(next) : 0;
        return (sign == '+' || sign == '-')
                && next + 1 < sql.length()
                && Character.isDigit(sql.charAt(next + 1));
    }

    private static boolean isLineCommentStart(String sql, int index) {
        return sql.charAt(index) == '-'
                && index + 1 < sql.length()
                && sql.charAt(index + 1) == '-';
    }

    private static boolean isBlockCommentStart(String sql, int index) {
        return sql.charAt(index) == '/'
                && index + 1 < sql.length()
                && sql.charAt(index + 1) == '*';
    }
}
