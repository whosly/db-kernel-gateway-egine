package com.whosly.gateway.adapter.mysql;

import java.util.Arrays;
import java.util.Optional;

/**
 * MySQL Client/Server Protocol capability flags recognized by the gateway.
 */
public enum MySQLCapability {
    CLIENT_LONG_PASSWORD(1L),
    CLIENT_FOUND_ROWS(1L << 1),
    CLIENT_LONG_FLAG(1L << 2),
    CLIENT_CONNECT_WITH_DB(1L << 3),
    CLIENT_NO_SCHEMA(1L << 4),
    CLIENT_COMPRESS(1L << 5),
    CLIENT_ODBC(1L << 6),
    CLIENT_LOCAL_FILES(1L << 7),
    CLIENT_IGNORE_SPACE(1L << 8),
    CLIENT_PROTOCOL_41(1L << 9),
    CLIENT_INTERACTIVE(1L << 10),
    CLIENT_SSL(1L << 11),
    CLIENT_IGNORE_SIGPIPE(1L << 12),
    CLIENT_TRANSACTIONS(1L << 13),
    CLIENT_RESERVED(1L << 14),
    CLIENT_RESERVED2(1L << 15),
    CLIENT_MULTI_STATEMENTS(1L << 16),
    CLIENT_MULTI_RESULTS(1L << 17),
    CLIENT_PS_MULTI_RESULTS(1L << 18),
    CLIENT_PLUGIN_AUTH(1L << 19),
    CLIENT_CONNECT_ATTRS(1L << 20),
    CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA(1L << 21),
    CLIENT_CAN_HANDLE_EXPIRED_PASSWORDS(1L << 22),
    CLIENT_SESSION_TRACK(1L << 23),
    CLIENT_DEPRECATE_EOF(1L << 24),
    CLIENT_OPTIONAL_RESULTSET_METADATA(1L << 25),
    CLIENT_ZSTD_COMPRESSION_ALGORITHM(1L << 26),
    CLIENT_QUERY_ATTRIBUTES(1L << 27),
    MULTI_FACTOR_AUTHENTICATION(1L << 28),
    CLIENT_CAPABILITY_EXTENSION(1L << 29),
    CLIENT_SSL_VERIFY_SERVER_CERT(1L << 30),
    CLIENT_REMEMBER_OPTIONS(1L << 31);

    private static final long RECOGNIZED_MASK = Arrays.stream(values())
            .mapToLong(MySQLCapability::getFlag)
            .reduce(0L, (left, right) -> left | right);

    private final long flag;

    MySQLCapability(long flag) {
        this.flag = flag;
    }

    public long getFlag() {
        return flag;
    }

    public static Optional<MySQLCapability> fromFlag(long flag) {
        return Arrays.stream(values())
                .filter(capability -> capability.flag == flag)
                .findFirst();
    }

    public static boolean isRecognized(long flag) {
        return flag != 0 && (flag & ~RECOGNIZED_MASK) == 0;
    }

    public static long recognizedMask() {
        return RECOGNIZED_MASK;
    }
}
