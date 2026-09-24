package com.whosly.gateway.adapter.protocol;

/**
 * Gateway-side failure that already carries its canonical error mapping.
 *
 * <p>Used when the gateway itself must answer the client, for example a risk
 * policy denial or a connection-limit rejection. The message is the sanitized
 * mapping description, so no internal detail leaks to the client.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class GatewayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final GatewayErrorMapping mapping;

    public GatewayException(GatewayErrorMapping mapping) {
        super(mapping.getDescription());
        this.mapping = mapping;
    }

    public GatewayErrorMapping getMapping() {
        return mapping;
    }
}
