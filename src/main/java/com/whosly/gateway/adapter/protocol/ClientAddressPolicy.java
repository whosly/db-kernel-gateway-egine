package com.whosly.gateway.adapter.protocol;

import java.net.InetAddress;

/**
 * Decides whether a connecting client address may use the gateway.
 *
 * <p>The gateway is transparent for authentication, so network-level admission
 * is the boundary that can be enforced without terminating the target
 * database's authentication exchange.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@FunctionalInterface
public interface ClientAddressPolicy {

    boolean isAllowed(InetAddress address);

    /**
     * @return a policy that admits every client address
     */
    static ClientAddressPolicy allowAll() {
        return address -> true;
    }
}
