package com.whosly.gateway.adapter.sqlserver;

/**
 * Common TDS tabular token type bytes (payload of {@link TdsPacketType#TABULAR_RESULT}).
 *
 * <p>Owned by this enum — docs mirror only. Observation and rewrite walk these
 * tokens; unsupported or unsafe tokens fail closed when masking is active.</p>
 */
public enum TdsTokenType {

    COLMETADATA(0x81),
    ALTMETADATA(0x88),
    ERROR(0xAA),
    INFO(0xAB),
    LOGINACK(0xAD),
    ROW(0xD1),
    NBCROW(0xD2),
    ENVCHANGE(0xE3),
    SESSIONSTATE(0xE4),
    SSPI(0xED),
    FEDAUTHINFO(0xEE),
    DONE(0xFD),
    DONEPROC(0xFE),
    DONEINPROC(0xFF);

    private final int code;

    TdsTokenType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static TdsTokenType fromCode(int code) {
        int normalized = code & 0xFF;
        for (TdsTokenType type : values()) {
            if (type.code == normalized) {
                return type;
            }
        }
        return null;
    }
}
