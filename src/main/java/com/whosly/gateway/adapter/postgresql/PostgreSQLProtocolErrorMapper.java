package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.GatewayErrorMapping;
import com.whosly.gateway.adapter.protocol.ProtocolErrorMapper;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Maps gateway-side failures to a PostgreSQL {@code ErrorResponse} message.
 *
 * <p>Only sanitized gateway descriptions are emitted; backend errors are
 * forwarded verbatim by the transparent relay and never pass through here.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class PostgreSQLProtocolErrorMapper implements ProtocolErrorMapper {

    /** Startup/auth-phase failures are fatal and close the connection. */
    private static final String FATAL_SEVERITY = "FATAL";

    @Override
    public ProtocolMessage toErrorMessage(Throwable error) {
        GatewayErrorMapping mapping = GatewayErrorMapping.fromThrowable(error);
        return ProtocolMessage.typed(PostgreSQLBackendMessageType.ERROR_RESPONSE.getCode(), errorPayload(mapping));
    }

    public GatewayErrorMapping resolve(Throwable error) {
        return GatewayErrorMapping.fromThrowable(error);
    }

    /**
     * Builds an ErrorResponse payload: {@code S}/{@code V} severity, {@code C}
     * SQLSTATE, {@code M} message, terminated by a zero byte.
     */
    public static byte[] errorPayload(GatewayErrorMapping mapping) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeField(output, 'S', FATAL_SEVERITY);
        writeField(output, 'V', FATAL_SEVERITY);
        writeField(output, 'C', mapping.getPostgreSqlState());
        writeField(output, 'M', mapping.getDescription());
        output.write(0);
        return output.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream output, char fieldCode, String value) {
        output.write(fieldCode);
        output.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        output.write(0);
    }
}
