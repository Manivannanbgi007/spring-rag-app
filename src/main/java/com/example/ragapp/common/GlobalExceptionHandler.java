package com.example.ragapp.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Turns exceptions into clean JSON error responses (standard "ProblemDetail" format).
 * One place for all error handling, so controllers stay simple.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Also covers InvalidDocumentException, which extends InvalidRequestException
    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail handleInvalidRequest(InvalidRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    public ProblemDetail handleConversationNotFound(ConversationNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConversationExpiredException.class)
    public ProblemDetail handleConversationExpired(ConversationExpiredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.GONE, e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ProblemDetail handleMissingFile(MissingServletRequestPartException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Missing form part '" + e.getRequestPartName() + "'. Send the file as multipart/form-data.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleTooLarge(MaxUploadSizeExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "The file is too large. The maximum upload size is 100MB.");
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ProblemDetail handleNotFound(DocumentNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DocumentConflictException.class)
    public ProblemDetail handleConflict(DocumentConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("existingDocumentId", e.getExistingDocumentId());
        return problem;
    }

    // Too busy right now: the client may try again soon
    @ExceptionHandler(ServiceBusyException.class)
    public ResponseEntity<ProblemDetail> handleBusy(ServiceBusyException e) {
        return withRetryAfter(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage() + " Please try again in a moment.", 5);
    }

    // One user or tenant asked too often
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException e) {
        return withRetryAfter(HttpStatus.TOO_MANY_REQUESTS, e.getMessage(), e.getRetryAfterSeconds());
    }

    // A request body that fails validation, for example an empty question
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .findFirst()
                .orElse("The request is not valid.");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The request body is missing or is not valid JSON.");
    }

    // The language model, embedding model or vector database is down
    @ExceptionHandler(AiServiceUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleAiUnavailable(AiServiceUnavailableException e) {
        log.error("AI service unavailable: {}", e.getMessage(), e.getCause());
        return withRetryAfter(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage() + " Please try again in a few minutes.", 30);
    }

    // Standard web errors that Spring already describes well: unknown address (404), wrong HTTP method (405),
    // unsupported content type (415), missing request parameter (400). Without this they would fall into the
    // safety net below and be reported as "500 Something went wrong", which is wrong: the caller made the mistake.
    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ProblemDetail> handleStandardWebErrors(Exception e) {
        ErrorResponse response = (ErrorResponse) e;
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .body(response.getBody());
    }

    // For example DELETE /api/documents/not-a-uuid
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleBadParameterType(MethodArgumentTypeMismatchException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The value of '" + e.getName() + "' is not valid.");
    }

    /** The "Retry-After" header tells well-behaved clients how many seconds to wait. */
    private ResponseEntity<ProblemDetail> withRetryAfter(HttpStatus status, String message, long seconds) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .body(ProblemDetail.forStatusAndDetail(status, message));
    }

    // Safety net: log the real error, but never show internal details to the caller
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. Please try again later.");
    }
}
