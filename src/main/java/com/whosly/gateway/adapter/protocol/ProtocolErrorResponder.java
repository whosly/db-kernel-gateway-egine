package com.whosly.gateway.adapter.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Writes a protocol-native error message to a client stream.
 *
 * <p>Combines the protocol frame codec (which owns the wire framing) with the
 * protocol error mapper (which owns the error message shape), so callers such
 * as the relay or an adapter can answer a client without knowing either.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class ProtocolErrorResponder {

    private final ProtocolFrameCodec frameCodec;
    private final ProtocolErrorMapper errorMapper;

    public ProtocolErrorResponder(ProtocolFrameCodec frameCodec, ProtocolErrorMapper errorMapper) {
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec must not be null");
        this.errorMapper = Objects.requireNonNull(errorMapper, "errorMapper must not be null");
    }

    /**
     * Maps the failure and writes the resulting message, then flushes.
     */
    public void respond(OutputStream outputStream, Throwable error) throws IOException {
        frameCodec.write(errorMapper.toErrorMessage(error), outputStream);
        outputStream.flush();
    }

    public ProtocolErrorMapper errorMapper() {
        return errorMapper;
    }
}
