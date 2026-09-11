package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.GatewayErrorMapping;
import com.whosly.gateway.adapter.protocol.ProtocolErrorMapper;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Maps gateway-side failures to MySQL {@code ERR_Packet} payloads.
 *
 * <p>Only sanitized gateway descriptions are emitted; backend SQL errors are
 * forwarded verbatim by the transparent relay and never pass through here.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class MySqlGatewayErrorMapper implements ProtocolErrorMapper {

    /** Server sequence id for the first packet a refusing MySQL server sends. */
    private static final int FIRST_SERVER_PACKET_SEQUENCE = 0;

    @Override
    public ProtocolMessage toErrorMessage(Throwable error) {
        return ProtocolMessage.untyped(errorPayload(resolve(error)), FIRST_SERVER_PACKET_SEQUENCE);
    }

    public GatewayErrorMapping resolve(Throwable error) {
        return GatewayErrorMapping.fromThrowable(error);
    }

    /**
     * Builds an ERR_Packet payload: {@code 0xFF} header, 2-byte error code,
     * SQLSTATE marker, 5-byte SQLSTATE, and a sanitized message.
     */
    public static byte[] errorPayload(GatewayErrorMapping mapping) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0xFF);
        output.write(mapping.getMySqlErrno() & 0xFF);
        output.write((mapping.getMySqlErrno() >> 8) & 0xFF);
        output.write('#');
        byte[] sqlState = mapping.getMySqlSqlState().getBytes(StandardCharsets.US_ASCII);
        for (int index = 0; index < 5; index++) {
            output.write(index < sqlState.length ? sqlState[index] : ' ');
        }
        output.writeBytes(mapping.getDescription().getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }
}
