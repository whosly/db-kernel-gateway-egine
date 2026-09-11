package com.whosly.gateway.masking;

/**
 * Masking could not be applied safely.
 *
 * <p>Masking is fail-closed: when a rule conflicts, cannot handle the column, or
 * fails, the message must not be forwarded as if it had been masked. Silently
 * forwarding the original value would be a false guarantee of protection.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class MaskingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MaskingException(String message) {
        super(message);
    }

    public MaskingException(String message, Throwable cause) {
        super(message, cause);
    }
}
