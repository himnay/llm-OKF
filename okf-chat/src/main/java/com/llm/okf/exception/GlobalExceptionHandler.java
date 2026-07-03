package com.llm.okf.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Maps Bean Validation failures to a structured 400 response listing each invalid field and its message.
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiError handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        FieldError::getDefaultMessage,
                        (a, b) -> b));
        return ApiError.of(400, "Validation Failed", "Request validation failed", fieldErrors);
    }

    /** Required query/path parameter absent — e.g. calling /resolve without ?file=. */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ApiError handleMissingParameter(org.springframework.web.bind.MissingServletRequestParameterException ex) {
        return ApiError.of(400, "Bad Request", ex.getMessage());
    }

    /** Invalid client input outside bean validation — e.g. a bad OKF query-file path or unsafe query. */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiError handleIllegalArgument(IllegalArgumentException ex) {
        return ApiError.of(400, "Bad Request", ex.getMessage());
    }

    /**
     * LLM backend call failed or was interrupted mid-flight (ollama down, request cancelled,
     * devtools restart) — reported as 503 without the full stack trace.
     */
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(org.springframework.web.client.ResourceAccessException.class)
    public ApiError handleLlmBackend(org.springframework.web.client.ResourceAccessException ex) {
        log.warn("LLM backend call failed: {}", ex.getMessage());
        return ApiError.of(503, "LLM Backend Unavailable",
                "The model backend did not complete the request: " + ex.getMessage());
    }

    /** Client disconnected before LLM finished — not an application error. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException ex) {
        log.warn("Client disconnected before response completed: {}", ex.getMessage());
    }

    /** Catches all unhandled exceptions and returns a 500 with the exception message. Logs at ERROR level. */
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public ApiError handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ApiError.of(500, "Internal Server Error", ex.getMessage());
    }
}
