package com.whosly.gateway.adapter.sqlserver;

/**
 * TDS packet status flags (header offset 1).
 */
public final class TdsPacketStatus {

    /** End of message — last packet of a logical TDS message. */
    public static final int NORMAL = 0x00;
    public static final int EOM = 0x01;
    public static final int IGNORE = 0x02;
    public static final int RESET_CONNECTION = 0x08;
    public static final int RESET_CONNECTION_SKIP_TRAN = 0x10;

    private TdsPacketStatus() {
    }

    public static boolean isEom(int status) {
        return (status & EOM) != 0;
    }
}
