package com.whosly.gateway.adapter.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Protocol-neutral contract for database wire frame codecs.
 */
public interface ProtocolFrameCodec {

    ProtocolMessage read(InputStream inputStream) throws IOException;

    void write(ProtocolMessage message, OutputStream outputStream) throws IOException;
}
