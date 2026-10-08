package com.whosly.gateway.adapter.sqlserver;

/**
 * TDS packet type byte (header offset 0). Owned by this enum — docs mirror only.
 *
 * <p>P0 focuses on PreLogin / Login7 / tabular response for transparent relay.
 * Deeper types (RPC, Attention, Bulk) are deferred to P2.</p>
 */
public enum TdsPacketType {

    SQL_BATCH(0x01),
    PRE_TDS7_LOGIN(0x02),
    RPC(0x03),
    TABULAR_RESULT(0x04),
    ATTENTION(0x06),
    BULK_LOAD(0x07),
    FED_AUTH_TOKEN(0x08),
    TRANSACTION_MANAGER(0x0E),
    TDS7_LOGIN(0x10),
    SSPI(0x11),
    PRELOGIN(0x12);

    private final int code;

    TdsPacketType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static TdsPacketType fromCode(int code) {
        int normalized = code & 0xFF;
        for (TdsPacketType type : values()) {
            if (type.code == normalized) {
                return type;
            }
        }
        return null;
    }
}
