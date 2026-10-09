package com.urlshortener.api;

import java.time.Instant;

/**
 * Body of {@code POST /v1/urls}. Field names reach the wire as snake_case
 * ({@code long_url}, {@code expires_at}) through the global Jackson naming strategy.
 *
 * <p>No validation annotations: the rules (scheme, length, own domain, expiry in the future)
 * live in {@code LongUrlValidator} and {@code ShortenService}, so they're enforced the same
 * way whatever calls the service, and tested once. A bean-validation {@code @NotBlank} here
 * would be a second, weaker copy of the same rule.
 *
 * <p>{@code custom_alias} arrives in M4. Until then Jackson ignores unknown fields, so a
 * client sending one gets a generated code instead.
 *
 * @param longUrl   destination URL, e.g. {@code https://www.example.com/products/shoes}
 * @param expiresAt optional ISO-8601 instant, e.g. {@code 2027-01-01T00:00:00Z}
 */
public record CreateUrlRequest(String longUrl, Instant expiresAt) {
}
