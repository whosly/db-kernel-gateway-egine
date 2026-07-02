package com.whosly.gateway.adapter.protocol;

/**
 * Canonical gateway errors mapped to protocol-native SQLSTATE and errno values.
 */
public enum GatewayErrorMapping {
    TARGET_UNAVAILABLE(1042, "08S01", "08006", "target database unavailable"),
    TARGET_CONNECTION_REJECTED(1040, "08004", "08004", "target database rejected connection"),
    TARGET_HANDSHAKE_FAILED(1043, "08S01", "08P01", "target database handshake failed"),
    ROUTE_NOT_FOUND(1105, "HY000", "08001", "target route not found"),
    UNSUPPORTED_GATEWAY_FEATURE(1235, "42000", "0A000", "unsupported gateway feature"),
    PROTOCOL_VIOLATION(1047, "08S01", "08P01", "protocol violation"),
    INTERNAL_GATEWAY_ERROR(1105, "HY000", "XX000", "internal gateway error"),
    RESOURCE_EXHAUSTED(1041, "HY000", "53200", "gateway resource exhausted"),
    CONNECTION_TIMEOUT(2013, "HY000", "08006", "target connection timed out"),
    GATEWAY_SHUTTING_DOWN(1053, "08S01", "57P01", "gateway shutting down");

    private final int mySqlErrno;
    private final String mySqlSqlState;
    private final String postgreSqlState;
    private final String description;

    GatewayErrorMapping(int mySqlErrno, String mySqlSqlState, String postgreSqlState, String description) {
        this.mySqlErrno = mySqlErrno;
        this.mySqlSqlState = mySqlSqlState;
        this.postgreSqlState = postgreSqlState;
        this.description = description;
    }

    public int getMySqlErrno() {
        return mySqlErrno;
    }

    public String getMySqlSqlState() {
        return mySqlSqlState;
    }

    public String getPostgreSqlState() {
        return postgreSqlState;
    }

    public String getDescription() {
        return description;
    }
}
