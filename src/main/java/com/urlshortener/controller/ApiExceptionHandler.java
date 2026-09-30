package com.urlshortener.controller;

import com.urlshortener.service.CodeAllocationException;
import com.urlshortener.service.InvalidLinkRequestException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 9457 {@code application/problem+json} bodies:
 * <pre>
 * { "type": "about:blank", "title": "Unprocessable Entity", "status": 422,
 *   "detail": "long_url must start with http:// or https://", "error_code": "INVALID_URL" }
 * </pre>
 *
 * <h2>Mapping</h2>
 * <pre>
 * exception                       status  error_code        whose fault   retry helps?
 * ------------------------------  ------  ----------------  ------------  ------------
 * InvalidLinkRequestException     422     its Reason        the caller    no
 * CodeAllocationException         503     CODE_ALLOCATION   ours          maybe (new random codes)
 * malformed JSON, bad date        400     (Spring's)        the caller    no
 * wrong method / media type       405/415 (Spring's)        the caller    no
 * </pre>
 * 422, not 400, for rule violations: the JSON was well-formed and understood (400 is for
 * that), but its content is unacceptable. The distinction lets clients tell "my request is
 * broken" from "my URL was refused".
 *
 * <h2>Why extend ResponseEntityExceptionHandler, and no catch-all</h2>
 * The base class already maps Spring MVC's own exceptions (bad JSON, 405, 415) to the right
 * status with a {@link ProblemDetail}, so every error has one shape. A hand-written
 * {@code @ExceptionHandler(Exception.class)} is a common trap: it also catches those framework
 * exceptions and turns a client's 405 or 400 into our 500. Anything truly unexpected falls
 * through to Spring Boot's default 500, which doesn't leak stack traces.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidLinkRequestException.class)
    ProblemDetail invalidRequest(InvalidLinkRequestException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e.reason().name());
    }

    /**
     * 503 + Retry-After, because with random codes a retry draws fresh codes and may succeed.
     * The service has already logged it as an error: it means something is wrong on our side.
     */
    @ExceptionHandler(CodeAllocationException.class)
    ResponseEntity<ProblemDetail> codeAllocation(CodeAllocationException e) {
        ProblemDetail body = problem(HttpStatus.SERVICE_UNAVAILABLE, "could not create a short link right now; try again", "CODE_ALLOCATION");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(body);
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        // Map keys aren't renamed by the Jackson naming strategy, so this is written as snake_case.
        body.setProperty("error_code", errorCode);
        return body;
    }
}
