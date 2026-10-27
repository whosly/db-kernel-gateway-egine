package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.masking.MaskingException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Encodes / decodes SQL Server {@code hierarchyid} path strings per [MS-SSCLRT].
 *
 * <p>Only slash-separated non-negative integer paths are supported
 * (for example {@code /}, {@code /1/}, {@code /1/2/}). Dotted "fake" levels
 * ({@code /1.1/}) remain fail-closed.</p>
 */
public final class TdsHierarchyId {

    private TdsHierarchyId() {
    }

    public static boolean isHierarchyIdTypeName(String typeName) {
        return typeName != null
                && "hierarchyid".equalsIgnoreCase(typeName.trim());
    }

    /**
     * @param path canonical path such as {@code /1/2/} or {@code /}
     */
    public static byte[] encode(String path) {
        Objects.requireNonNull(path, "path");
        String normalized = path.trim();
        if (!normalized.startsWith("/")) {
            throw new MaskingException("hierarchyid path must start with /");
        }
        if (normalized.contains(".")) {
            throw new MaskingException("hierarchyid dotted (fake) levels are not supported");
        }
        if ("/".equals(normalized)) {
            // SQL Server CAST('/' AS hierarchyid) is empty varbinary.
            return new byte[0];
        }
        if (!normalized.endsWith("/")) {
            throw new MaskingException("hierarchyid path must end with /");
        }
        String body = normalized.substring(1, normalized.length() - 1);
        if (body.isEmpty()) {
            return new byte[0];
        }
        String[] parts = body.split("/");
        BitSink bits = new BitSink();
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new MaskingException("hierarchyid empty segment");
            }
            long value;
            try {
                value = Long.parseLong(part);
            } catch (NumberFormatException e) {
                throw new MaskingException("hierarchyid segment is not an integer: " + part);
            }
            if (value < 0) {
                throw new MaskingException("hierarchyid negative segments are not supported");
            }
            appendLevel(bits, value);
        }
        return bits.toBytes();
    }

    public static String decode(byte[] wire) {
        Objects.requireNonNull(wire, "wire");
        if (wire.length == 0) {
            return "/";
        }
        BitSource bits = new BitSource(wire);
        StringBuilder path = new StringBuilder("/");
        while (bits.remaining() > 0) {
            // Trailing zero padding — stop when only zeros remain.
            if (bits.remainingOnes() == 0) {
                break;
            }
            long value = readLevel(bits);
            path.append(value).append('/');
        }
        return path.toString();
    }

    public static Optional<byte[]> tryEncode(String path) {
        try {
            return Optional.of(encode(path));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static Optional<String> tryDecode(byte[] wire) {
        try {
            return Optional.of(decode(wire));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static void appendLevel(BitSink bits, long n) {
        // Real level (Fi=1). Li/Oi ranges from [MS-SSCLRT] for non-negative n.
        if (n <= 3) {
            // Li=01, Oi=2 bits, F=1
            bits.writeBits(0b01, 2);
            bits.writeBits((int) n, 2);
            bits.writeBits(1, 1);
            return;
        }
        if (n <= 7) {
            // Li=100, Oi=2 bits (n-4), F=1
            bits.writeBits(0b100, 3);
            bits.writeBits((int) (n - 4), 2);
            bits.writeBits(1, 1);
            return;
        }
        if (n <= 15) {
            // Li=101, Oi=3 bits (n-8), F=1
            bits.writeBits(0b101, 3);
            bits.writeBits((int) (n - 8), 3);
            bits.writeBits(1, 1);
            return;
        }
        if (n <= 79) {
            // Li=110, Oi=6+antiambiguity, F=1 — format 110..0.1... with Oi 8 bits w/ anti
            // Full format: 110 + 6 data with antiambiguity inserted → use 8-bit Oi field
            // 110..0.1... means: 110, then 2 bits, 0, 1 bit, 1, 3 bits? 
            // From table: Bit size without/with anti = 6/8; format 110..0.1...
            // Simpler empirical approach for 16..79: 
            // 110 | (n-16) with antiambiguity bits at fixed positions.
            int offset = (int) (n - 16);
            // Without anti: 6 bits. With anti at positions yielding 8-bit field:
            // format 110 .. 0 . 1 ...  → Li(3) + o5 o4 0 o3 1 o2 o1 o0  + F?
            // Actually F is separate after Oi. Format column shows Li/Oi pair only.
            // 110..0.1... = 3 + 8 = 11 bits for Li/Oi, then F=1.
            bits.writeBits(0b110, 3);
            // Pack 6-bit value into 8-bit field with antiambiguity 0 and 1 at fixed spots.
            // Pattern: bit5 bit4 0 bit3 1 bit2 bit1 bit0  where bit5..bit0 = offset
            int v = offset & 0x3F;
            int packed = ((v & 0x30) << 2) | (0 << 5) | ((v & 0x08) << 1) | (1 << 3) | (v & 0x07);
            // Recheck: "110..0.1..." dots are Oi data bits. Positions:
            // 110 | d5 d4 | 0 | d3 | 1 | d2 d1 d0
            packed = ((v >> 4) & 0x03) << 6
                    | (0 << 5)
                    | (((v >> 3) & 0x01) << 4)
                    | (1 << 3)
                    | (v & 0x07);
            bits.writeBits(packed, 8);
            bits.writeBits(1, 1);
            return;
        }
        if (n <= 1103) {
            // Li=1110, Oi 10/13 — format 1110...0...0.1...
            int offset = (int) (n - 80);
            bits.writeBits(0b1110, 4);
            // 10 data bits in 13-bit field with antiambiguity.
            // Pattern from SO for /80../1103/: 1110 aaa 0 zzz 0 y 1 xxx
            // We'll pack 10-bit offset as: 3bits + 0 + 3bits + 0 + 1bit + 1 + 3bits
            int v = offset & 0x3FF;
            int aaa = (v >> 7) & 0x07;
            int zzz = (v >> 4) & 0x07;
            int y = (v >> 3) & 0x01;
            int xxx = v & 0x07;
            int packed = (aaa << 10) | (0 << 9) | (zzz << 6) | (0 << 5) | (y << 4) | (1 << 3) | xxx;
            bits.writeBits(packed, 13);
            bits.writeBits(1, 1);
            return;
        }
        throw new MaskingException("hierarchyid segment out of supported range: " + n);
    }

    private static long readLevel(BitSource bits) {
        // Peek Li prefixes.
        if (bits.peekBits(2) == 0b01) {
            bits.readBits(2);
            long n = bits.readBits(2);
            int f = bits.readBits(1);
            if (f != 1) {
                throw new MaskingException("hierarchyid fake level not supported");
            }
            return n;
        }
        if (bits.peekBits(3) == 0b100) {
            bits.readBits(3);
            long n = bits.readBits(2) + 4;
            if (bits.readBits(1) != 1) {
                throw new MaskingException("hierarchyid fake level not supported");
            }
            return n;
        }
        if (bits.peekBits(3) == 0b101) {
            bits.readBits(3);
            long n = bits.readBits(3) + 8;
            if (bits.readBits(1) != 1) {
                throw new MaskingException("hierarchyid fake level not supported");
            }
            return n;
        }
        if (bits.peekBits(3) == 0b110) {
            bits.readBits(3);
            int packed = bits.readBits(8);
            // Inverse of 110 d5 d4 0 d3 1 d2 d1 d0
            int d5d4 = (packed >> 6) & 0x03;
            int d3 = (packed >> 4) & 0x01;
            int d210 = packed & 0x07;
            long n = ((d5d4 << 4) | (d3 << 3) | d210) + 16;
            if (bits.readBits(1) != 1) {
                throw new MaskingException("hierarchyid fake level not supported");
            }
            return n;
        }
        if (bits.peekBits(4) == 0b1110) {
            bits.readBits(4);
            int packed = bits.readBits(13);
            int aaa = (packed >> 10) & 0x07;
            int zzz = (packed >> 6) & 0x07;
            int y = (packed >> 4) & 0x01;
            int xxx = packed & 0x07;
            long n = ((aaa << 7) | (zzz << 4) | (y << 3) | xxx) + 80;
            if (bits.readBits(1) != 1) {
                throw new MaskingException("hierarchyid fake level not supported");
            }
            return n;
        }
        throw new MaskingException("unsupported hierarchyid Li prefix");
    }

    /** Hex parse helper for opaque UDT fixed values ({@code 0x…} / {@code 0X…}). */
    public static Optional<byte[]> tryParseHexBytes(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.trim();
        if (t.length() < 3 || !t.toLowerCase(Locale.ROOT).startsWith("0x")) {
            return Optional.empty();
        }
        String hex = t.substring(2).replace(" ", "");
        if (hex.length() % 2 != 0) {
            return Optional.empty();
        }
        try {
            byte[] out = new byte[hex.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return Optional.of(out);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static String toHexLiteral(byte[] bytes) {
        StringBuilder sb = new StringBuilder("0x");
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private static final class BitSink {
        private final List<Integer> bits = new ArrayList<>();

        void writeBits(int value, int width) {
            for (int i = width - 1; i >= 0; i--) {
                bits.add((value >> i) & 1);
            }
        }

        byte[] toBytes() {
            int len = (bits.size() + 7) / 8;
            byte[] out = new byte[len];
            for (int i = 0; i < bits.size(); i++) {
                if (bits.get(i) != 0) {
                    out[i / 8] |= (byte) (1 << (7 - (i % 8)));
                }
            }
            return out;
        }
    }

    private static final class BitSource {
        private final byte[] data;
        private int bitPos;

        BitSource(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length * 8 - bitPos;
        }

        int remainingOnes() {
            int ones = 0;
            for (int i = bitPos; i < data.length * 8; i++) {
                int byteIndex = i / 8;
                int bit = 7 - (i % 8);
                if (((data[byteIndex] >> bit) & 1) != 0) {
                    ones++;
                }
            }
            return ones;
        }

        int peekBits(int width) {
            int saved = bitPos;
            int value = readBits(width);
            bitPos = saved;
            return value;
        }

        int readBits(int width) {
            int value = 0;
            for (int i = 0; i < width; i++) {
                if (bitPos >= data.length * 8) {
                    throw new MaskingException("hierarchyid truncated");
                }
                int byteIndex = bitPos / 8;
                int bit = 7 - (bitPos % 8);
                value = (value << 1) | ((data[byteIndex] >> bit) & 1);
                bitPos++;
            }
            return value;
        }
    }
}
