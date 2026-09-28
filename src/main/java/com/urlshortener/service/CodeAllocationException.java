package com.urlshortener.service;

/**
 * Every insert attempt hit an existing code. With the retry budget sized from the collision
 * math ({@link ShortenService}), this should essentially never happen through bad luck, so
 * it means something is broken: a generator stuck on one value, a range handed out twice,
 * or a keyspace far fuller than planned.
 *
 * <p>It's our fault, not the caller's, so it maps to a 5xx and is logged as an error.
 */
public class CodeAllocationException extends RuntimeException {

    public CodeAllocationException(String message) {
        super(message);
    }
}
