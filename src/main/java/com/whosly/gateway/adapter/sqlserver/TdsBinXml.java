package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.masking.MaskingException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal MS-BINXML encode/decode for TDS {@code xml} column masking.
 *
 * <p>Non-null XML values on the wire are PLP payloads of Binary XML
 * ([MS-BINXML]), not UCS-2 text. Rules operate on UTF-8 text; this class
 * wraps rule text in a synthetic {@code <m>…</m>} document for rewrite, and
 * best-effort decodes simple documents back to text for partial/hash.</p>
 */
public final class TdsBinXml {

    static final byte[] SIGNATURE = {(byte) 0xDF, (byte) 0xFF};
    static final byte VERSION_1 = 0x01;
    static final byte ENCODING_LO = (byte) 0xB0;
    static final byte ENCODING_HI = 0x04;

    static final int TOKEN_SQL_NVARCHAR = 0x11;
    static final int TOKEN_QNAMEDEF = 0xEF;
    static final int TOKEN_NAMEDEF = 0xF0;
    static final int TOKEN_ENDELEMENT = 0xF7;
    static final int TOKEN_ELEMENT = 0xF8;

    /** Root local name used when encoding rule text. */
    static final String SYNTHETIC_ROOT = "m";

    private TdsBinXml() {
    }

    /**
     * Encodes {@code text} as BINXML document {@code <m>text</m>} (empty
     * element when text is empty).
     */
    public static byte[] encodeTextDocument(String text) {
        Objects.requireNonNull(text, "text");
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 + text.length() * 2);
        out.write(SIGNATURE[0]);
        out.write(SIGNATURE[1]);
        out.write(VERSION_1);
        out.write(ENCODING_LO);
        out.write(ENCODING_HI);

        // NAMEDEF "m" → name id 1
        out.write(TOKEN_NAMEDEF);
        writeTextdata(out, SYNTHETIC_ROOT);
        // QNAMEDEF ns=0 prefix=0 local=1 → qname id 1
        out.write(TOKEN_QNAMEDEF);
        writeMb32(out, 0);
        writeMb32(out, 0);
        writeMb32(out, 1);
        // ELEMENT qname 1
        out.write(TOKEN_ELEMENT);
        writeMb32(out, 1);
        if (!text.isEmpty()) {
            out.write(TOKEN_SQL_NVARCHAR);
            writeTextdata(out, text);
        }
        out.write(TOKEN_ENDELEMENT);
        return out.toByteArray();
    }

    /**
     * Best-effort decode of a simple BINXML document to text XML.
     *
     * @return empty when the payload is not a recognizable Binary XML document
     */
    public static Optional<String> tryDecodeToText(byte[] binxml) {
        if (binxml == null || binxml.length < 5) {
            return Optional.empty();
        }
        if ((binxml[0] & 0xFF) != 0xDF || (binxml[1] & 0xFF) != 0xFF) {
            return Optional.empty();
        }
        int version = binxml[2] & 0xFF;
        if (version != 0x01 && version != 0x02) {
            return Optional.empty();
        }
        if ((binxml[3] & 0xFF) != 0xB0 || (binxml[4] & 0xFF) != 0x04) {
            return Optional.empty();
        }
        try {
            StringBuilder xml = new StringBuilder();
            String[] names = new String[64];
            names[0] = "";
            int nameCount = 0;
            // qname local-name indices (1-based qname → local name index)
            int[] qnameLocal = new int[64];
            int qnameCount = 0;
            java.util.ArrayDeque<String> open = new java.util.ArrayDeque<>();
            int cursor = 5;
            while (cursor < binxml.length) {
                int token = binxml[cursor] & 0xFF;
                cursor++;
                switch (token) {
                    case TOKEN_NAMEDEF -> {
                        Optional<TextParse> name = readTextdata(binxml, cursor);
                        if (name.isEmpty()) {
                            return Optional.empty();
                        }
                        nameCount++;
                        if (nameCount < names.length) {
                            names[nameCount] = name.get().text();
                        }
                        cursor = name.get().nextOffset();
                    }
                    case TOKEN_QNAMEDEF -> {
                        OptionalIntParse ns = readMb32(binxml, cursor);
                        if (ns.isEmpty()) {
                            return Optional.empty();
                        }
                        cursor = ns.nextOffset();
                        OptionalIntParse prefix = readMb32(binxml, cursor);
                        if (prefix.isEmpty()) {
                            return Optional.empty();
                        }
                        cursor = prefix.nextOffset();
                        OptionalIntParse local = readMb32(binxml, cursor);
                        if (local.isEmpty()) {
                            return Optional.empty();
                        }
                        cursor = local.nextOffset();
                        qnameCount++;
                        if (qnameCount < qnameLocal.length) {
                            qnameLocal[qnameCount] = local.value();
                        }
                    }
                    case TOKEN_ELEMENT -> {
                        OptionalIntParse qn = readMb32(binxml, cursor);
                        if (qn.isEmpty()) {
                            return Optional.empty();
                        }
                        cursor = qn.nextOffset();
                        String localName = resolveLocal(names, qnameLocal, qn.value());
                        open.push(localName);
                        xml.append('<').append(localName).append('>');
                    }
                    case TOKEN_ENDELEMENT -> {
                        if (open.isEmpty()) {
                            return Optional.empty();
                        }
                        String localName = open.pop();
                        xml.append("</").append(localName).append('>');
                    }
                    case TOKEN_SQL_NVARCHAR, 0x0E /* SQL-NCHAR */ -> {
                        Optional<TextParse> text = readTextdata(binxml, cursor);
                        if (text.isEmpty()) {
                            return Optional.empty();
                        }
                        xml.append(escapeXmlText(text.get().text()));
                        cursor = text.get().nextOffset();
                    }
                    case 0xF5 /* ENDATTRIBUTES */ -> {
                        // ignore
                    }
                    case 0xF3 /* COMMENT */ -> {
                        Optional<TextParse> text = readTextdata(binxml, cursor);
                        if (text.isEmpty()) {
                            return Optional.empty();
                        }
                        cursor = text.get().nextOffset();
                    }
                    default -> {
                        // Unsupported token — refuse rather than guess.
                        return Optional.empty();
                    }
                }
            }
            if (!open.isEmpty()) {
                return Optional.empty();
            }
            String result = xml.toString();
            return result.isEmpty() ? Optional.empty() : Optional.of(result);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static byte[] encodeOrThrow(String text) {
        try {
            return encodeTextDocument(text);
        } catch (RuntimeException e) {
            throw new MaskingException("BINXML encode failed: " + e.getMessage());
        }
    }

    public static String decodeOrThrow(byte[] binxml, String columnName) {
        return tryDecodeToText(binxml).orElseThrow(() ->
                new MaskingException("cannot decode MS-BINXML for column " + columnName));
    }

    private static String resolveLocal(String[] names, int[] qnameLocal, int qnameId) {
        if (qnameId <= 0 || qnameId >= qnameLocal.length) {
            return "x";
        }
        int localId = qnameLocal[qnameId];
        if (localId <= 0 || localId >= names.length || names[localId] == null) {
            return "x";
        }
        return names[localId];
    }

    private static String escapeXmlText(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static void writeTextdata(ByteArrayOutputStream out, String text) {
        byte[] utf16 = text.getBytes(StandardCharsets.UTF_16LE);
        int chars = utf16.length / 2;
        writeMb32(out, chars);
        out.write(utf16, 0, utf16.length);
    }

    private static void writeMb32(ByteArrayOutputStream out, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("mb32 negative");
        }
        // Little-endian multi-byte: high bytes have bit7 set; last clears it.
        while (value > 0x7F) {
            out.write((value & 0x7F) | 0x80);
            value >>= 7;
        }
        out.write(value & 0x7F);
    }

    private static Optional<TextParse> readTextdata(byte[] data, int offset) {
        OptionalIntParse len = readMb32(data, offset);
        if (len.isEmpty()) {
            return Optional.empty();
        }
        int chars = len.value();
        int bytes = chars * 2;
        int start = len.nextOffset();
        if (start + bytes > data.length) {
            return Optional.empty();
        }
        String text = chars == 0
                ? ""
                : new String(data, start, bytes, StandardCharsets.UTF_16LE);
        return Optional.of(new TextParse(text, start + bytes));
    }

    private static OptionalIntParse readMb32(byte[] data, int offset) {
        int value = 0;
        int shift = 0;
        int cursor = offset;
        for (int i = 0; i < 5; i++) {
            if (cursor >= data.length) {
                return OptionalIntParse.empty();
            }
            int b = data[cursor] & 0xFF;
            cursor++;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new OptionalIntParse(value, cursor);
            }
            shift += 7;
        }
        return OptionalIntParse.empty();
    }

    private record TextParse(String text, int nextOffset) {
    }

    private static final class OptionalIntParse {
        private final int value;
        private final int nextOffset;
        private final boolean present;

        private OptionalIntParse(int value, int nextOffset) {
            this.value = value;
            this.nextOffset = nextOffset;
            this.present = true;
        }

        private OptionalIntParse() {
            this.value = 0;
            this.nextOffset = 0;
            this.present = false;
        }

        static OptionalIntParse empty() {
            return new OptionalIntParse();
        }

        boolean isEmpty() {
            return !present;
        }

        int value() {
            return value;
        }

        int nextOffset() {
            return nextOffset;
        }
    }
}
