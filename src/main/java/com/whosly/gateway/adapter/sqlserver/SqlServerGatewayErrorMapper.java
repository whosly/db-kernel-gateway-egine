package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.GatewayErrorMapping;
import com.whosly.gateway.adapter.protocol.ProtocolErrorMapper;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;

import java.io.ByteArrayOutputStream;

/**
 * Maps gateway-side failures to a TDS {@code TABULAR_RESULT} containing ERROR + DONE.
 *
 * <p>Only sanitized gateway descriptions are emitted; backend SQL errors are
 * forwarded verbatim by the transparent relay and never pass through here.</p>
 *
 * <p>Number reuses the MySQL errno column of {@link GatewayErrorMapping} as a
 * stable gateway error id (not a SQL Server catalog number). Severity Class is
 * chosen to match MS-TDS class bands (resource / security / connection).</p>
 */
public class SqlServerGatewayErrorMapper implements ProtocolErrorMapper {

    /** ServerName shown to clients for forged errors. */
    public static final String GATEWAY_SERVER_NAME = "db-kernel-gateway";

    private static final int PACKET_ID = 1;

    @Override
    public ProtocolMessage toErrorMessage(Throwable error) {
        return ProtocolMessage.typed(
                (char) TdsPacketType.TABULAR_RESULT.getCode(),
                errorAndDonePayload(resolve(error)),
                PACKET_ID);
    }

    public GatewayErrorMapping resolve(Throwable error) {
        return GatewayErrorMapping.fromThrowable(error);
    }

    /**
     * Builds ERROR token + DONE(ERROR) as a single tabular payload.
     */
    public static byte[] errorAndDonePayload(GatewayErrorMapping mapping) {
        byte[] error = TdsErrorToken.encode(
                mapping.getMySqlErrno(),
                1,
                severityClass(mapping),
                mapping.getDescription(),
                GATEWAY_SERVER_NAME);
        byte[] done = TdsDoneToken.encodeErrorFinal();
        ByteArrayOutputStream out = new ByteArrayOutputStream(error.length + done.length);
        out.writeBytes(error);
        out.writeBytes(done);
        return out.toByteArray();
    }

    static int severityClass(GatewayErrorMapping mapping) {
        return switch (mapping) {
            case RISK_DENIED -> 14;
            case RESOURCE_EXHAUSTED -> 17;
            case TARGET_UNAVAILABLE, TARGET_CONNECTION_REJECTED, CONNECTION_TIMEOUT,
                    GATEWAY_SHUTTING_DOWN -> 20;
            case PROTOCOL_VIOLATION, TARGET_HANDSHAKE_FAILED -> 16;
            default -> 16;
        };
    }
}
