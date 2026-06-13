package dev.javalitellm.proxy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.core.exception.LiteLlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps the unified exception hierarchy to OpenAI-style error bodies. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ObjectMapper mapper;

    public ApiExceptionHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(LiteLlmException.class)
    public ResponseEntity<ObjectNode> handle(LiteLlmException e) {
        int status = e.statusCode() >= 400 && e.statusCode() <= 599 ? e.statusCode() : 500;
        ObjectNode body = mapper.createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("message", e.getMessage());
        error.put("type", e.getClass().getSimpleName());
        error.put("code", status);
        if (e.provider() != null) {
            error.put("provider", e.provider());
        }
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ObjectNode> handleUnexpected(Exception e) {
        log.error("unexpected proxy error", e);
        ObjectNode body = mapper.createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("message", "internal proxy error");
        error.put("type", "internal_error");
        error.put("code", 500);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
