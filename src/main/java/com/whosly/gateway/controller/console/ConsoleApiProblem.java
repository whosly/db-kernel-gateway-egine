package com.whosly.gateway.controller.console;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RFC 7807 problem+json body for Console API v1.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleApiProblem(
        String type,
        String title,
        int status,
        String detail,
        String code,
        String instance
) {
    public static final String MEDIA_TYPE = "application/problem+json";

    public static ConsoleApiProblem of(int status, String title, String detail, String code, String instance) {
        return new ConsoleApiProblem("about:blank", title, status, detail, code, instance);
    }
}
