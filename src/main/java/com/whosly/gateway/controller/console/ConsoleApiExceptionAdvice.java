package com.whosly.gateway.controller.console;

import com.whosly.gateway.console.schema.InstanceSchemaColumnsService.SchemaConnectException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared exception mapping for all /console/api controllers. */
@RestControllerAdvice(basePackages = "com.whosly.gateway.controller.console")
public class ConsoleApiExceptionAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException ex) {
        return errorBody(ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, Object> serviceUnavailable(IllegalStateException ex) {
        return errorBody(ex.getMessage());
    }

    @ExceptionHandler(SchemaConnectException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, Object> badGateway(SchemaConnectException ex) {
        return errorBody(ex.getMessage());
    }

    static Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", message);
        return body;
    }
}
