package com.whosly.gateway.adapter.sqlserver;

import java.util.OptionalInt;

/**
 * Minimal PRELOGIN option reader (MS-TDS).
 *
 * <p>Used by deferred Login7 routing to detect ENCRYPTION negotiation that would
 * hide Login7 behind TLS — same abort-to-bootstrap idea as MySQL SSLRequest.</p>
 */
public final class TdsPreLoginOptions {

    public static final int TOKEN_VERSION = 0x00;
    public static final int TOKEN_ENCRYPTION = 0x01;
    public static final int TOKEN_TERMINATOR = 0xFF;

    public static final int ENCRYPT_OFF = 0x00;
    public static final int ENCRYPT_ON = 0x01;
    public static final int ENCRYPT_NOT_SUP = 0x02;
    public static final int ENCRYPT_REQ = 0x03;

    private TdsPreLoginOptions() {
    }

    /**
     * True when cleartext Login7 is not expected after this PreLogin exchange
     * (client asked ENCRYPT_ON, or server answered ENCRYPT_ON / ENCRYPT_REQ).
     */
    public static boolean requiresEncryptionBeforeLogin7(byte[] clientPreLoginPayload,
                                                         byte[] serverPreLoginPayload) {
        OptionalInt client = encryptionValue(clientPreLoginPayload);
        OptionalInt server = encryptionValue(serverPreLoginPayload);
        if (client.isPresent() && client.getAsInt() == ENCRYPT_ON) {
            return true;
        }
        if (server.isPresent()) {
            int v = server.getAsInt();
            return v == ENCRYPT_ON || v == ENCRYPT_REQ;
        }
        return false;
    }

    public static OptionalInt encryptionValue(byte[] preLoginPayload) {
        if (preLoginPayload == null || preLoginPayload.length == 0) {
            return OptionalInt.empty();
        }
        int cursor = 0;
        while (cursor < preLoginPayload.length) {
            int token = preLoginPayload[cursor] & 0xFF;
            if (token == TOKEN_TERMINATOR) {
                return OptionalInt.empty();
            }
            if (cursor + 5 > preLoginPayload.length) {
                return OptionalInt.empty();
            }
            int dataOffset = u16be(preLoginPayload, cursor + 1);
            int dataLength = u16be(preLoginPayload, cursor + 3);
            cursor += 5;
            if (token == TOKEN_ENCRYPTION) {
                if (dataLength < 1 || dataOffset < 0 || dataOffset + dataLength > preLoginPayload.length) {
                    return OptionalInt.empty();
                }
                return OptionalInt.of(preLoginPayload[dataOffset] & 0xFF);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Minimal client/server PRELOGIN payload: VERSION (6 bytes) + ENCRYPTION (1) + terminator.
     * Offsets are big-endian per MS-TDS.
     */
    public static byte[] buildMinimal(int encryption) {
        // Option headers: VERSION at data offset 16, ENCRYPTION at 22, then terminator.
        // Layout: [5+5+1 headers][6 version][1 encrypt] = 18 bytes headers+term? 
        // headers = VERSION(5) + ENCRYPTION(5) + TERMINATOR(1) = 11
        // data starts at 11: version 6 bytes + encrypt 1 = 7 → total 18
        byte[] out = new byte[18];
        int dataStart = 11;
        // VERSION token
        out[0] = TOKEN_VERSION;
        putU16be(out, 1, dataStart);
        putU16be(out, 3, 6);
        // ENCRYPTION token
        out[5] = TOKEN_ENCRYPTION;
        putU16be(out, 6, dataStart + 6);
        putU16be(out, 8, 1);
        out[10] = (byte) TOKEN_TERMINATOR;
        // version bytes (UL_VERSION + US_SUBBUILD) — zeros fine for tests
        out[dataStart + 6] = (byte) (encryption & 0xFF);
        return out;
    }

    private static int u16be(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static void putU16be(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) ((value >> 8) & 0xFF);
        bytes[offset + 1] = (byte) (value & 0xFF);
    }
}
