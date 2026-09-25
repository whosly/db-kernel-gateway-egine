package com.whosly.gateway.controller.console;

import com.whosly.gateway.console.schema.InstanceSchemaColumnsService.SchemaConnectException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared exception mapping for Console API v1 → RFC 7807 problem+json.
 */
@RestControllerAdvice(basePackages = {
        "com.whosly.gateway.controller.console",
        "com.whosly.gateway.console.security"
})
public class ConsoleApiExceptionAdvice {

    private static final MediaType PROBLEM = MediaType.parseMediaType(ConsoleApiProblem.MEDIA_TYPE);


    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ConsoleApiProblem> forbidden(AccessDeniedException ex, HttpServletRequest req) {
        String detail = ex.getMessage() != null && !ex.getMessage().isBlank()
                ? ex.getMessage() : "Forbidden";
        return problem(HttpStatus.FORBIDDEN, "Forbidden", detail, "FORBIDDEN", req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ConsoleApiProblem> badRequest(IllegalArgumentException ex, HttpServletRequest req) {
        String detail = ex.getMessage() != null ? ex.getMessage() : "Bad request";
        HttpStatus status = looksLikeNotFound(detail) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        String code = status == HttpStatus.NOT_FOUND ? "NOT_FOUND" : "BAD_REQUEST";
        return problem(status, status.getReasonPhrase(), detail, code, req);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ConsoleApiProblem> serviceUnavailable(IllegalStateException ex, HttpServletRequest req) {
        String detail = ex.getMessage() != null ? ex.getMessage() : "Service unavailable";
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", detail, "SERVICE_UNAVAILABLE", req);
    }

    @ExceptionHandler(SchemaConnectException.class)
    public ResponseEntity<ConsoleApiProblem> badGateway(SchemaConnectException ex, HttpServletRequest req) {
        String detail = ex.getMessage() != null ? ex.getMessage() : "Upstream schema connect failed";
        return problem(HttpStatus.BAD_GATEWAY, "Bad Gateway", detail, "BAD_GATEWAY", req);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ConsoleApiProblem> status(ResponseStatusException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String detail = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        return problem(status, status.getReasonPhrase(), detail, status.name(), req);
    }

    private static boolean looksLikeNotFound(String detail) {
        String d = detail.toLowerCase();
        return d.contains("unknown") || d.contains("not found") || d.contains("不存在");
    }

    private static ResponseEntity<ConsoleApiProblem> problem(
            HttpStatus status, String title, String detail, String code, HttpServletRequest req) {
        String instance = req != null ? req.getRequestURI() : null;
        return ResponseEntity.status(status)
                .contentType(PROBLEM)
                .body(ConsoleApiProblem.of(status.value(), title, detail, code, instance));
    }
}
