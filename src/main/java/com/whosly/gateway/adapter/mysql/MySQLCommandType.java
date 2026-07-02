package com.whosly.gateway.adapter.mysql;

import java.util.Arrays;
import java.util.Optional;

/**
 * MySQL command phase command codes.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public enum MySQLCommandType {
    COM_QUIT(0x01),
    COM_INIT_DB(0x02),
    COM_QUERY(0x03),
    COM_FIELD_LIST(0x04),
    COM_CREATE_DB(0x05),
    COM_DROP_DB(0x06),
    COM_REFRESH(0x08),
    COM_STATISTICS(0x09),
    COM_PROCESS_INFO(0x0A),
    COM_CONNECT(0x0B),
    COM_PROCESS_KILL(0x0C),
    COM_DEBUG(0x0D),
    COM_PING(0x0E),
    COM_CHANGE_USER(0x11),
    COM_STMT_PREPARE(0x16),
    COM_STMT_EXECUTE(0x17),
    COM_STMT_CLOSE(0x19),
    COM_STMT_RESET(0x1A);

    private final int code;

    MySQLCommandType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static Optional<MySQLCommandType> fromCode(int code) {
        return Arrays.stream(values())
                .filter(commandType -> commandType.code == code)
                .findFirst();
    }
}
