package com.whosly.gateway.console.observe;

/** Outcome of one channel delivery attempt. */
public record AlertDeliveryResult(boolean ok, String message, Integer statusCode) {

    public static AlertDeliveryResult success(String message, Integer statusCode) {
        return new AlertDeliveryResult(true, message, statusCode);
    }

    public static AlertDeliveryResult failure(String message, Integer statusCode) {
        return new AlertDeliveryResult(false, message, statusCode);
    }
}
