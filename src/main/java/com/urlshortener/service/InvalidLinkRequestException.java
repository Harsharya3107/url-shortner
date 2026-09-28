package com.urlshortener.service;

/**
 * The caller asked for something we won't create. Always the caller's fault, so the
 * controller maps it to {@code 422 Unprocessable Entity} with {@link #reason()} as the error
 * code. Retrying the same request will fail the same way.
 */
public class InvalidLinkRequestException extends RuntimeException {

    public enum Reason {
        /** Not a parseable absolute http(s) URL, or too long. */
        INVALID_URL,
        /** Parseable, but points somewhere we refuse to redirect to (our own domain, credentials in the URL). */
        URL_NOT_ALLOWED,
        /** Expiry is not in the future. */
        INVALID_EXPIRY
    }

    private final Reason reason;

    public InvalidLinkRequestException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
