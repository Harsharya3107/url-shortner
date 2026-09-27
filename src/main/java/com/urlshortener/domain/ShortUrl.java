package com.urlshortener.domain;

import com.urlshortener.codegen.Base62;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * One short link: a code and the URL it redirects to. Maps one-to-one onto a
 * {@code short_urls} row (see {@code V1__create_short_urls.sql}).
 *
 * <h2>Why an immutable record, not a JPA entity</h2>
 * <pre>
 * JPA entity + save()                         record + explicit SQL
 * ------------------------------------------  ------------------------------------------
 * assigned id → save() calls merge():          INSERT ... ON CONFLICT (code) DO NOTHING
 *   SELECT, then INSERT or UPDATE              → 1 row = ours, 0 rows = taken, retry
 * a colliding code UPDATES someone else's     a colliding code can never touch an
 *   link, silently                             existing row
 * mutable, dirty-checked, lazy proxies         a plain value, safe to cache (M3) and share
 * </pre>
 * The redirect path reads a link and never changes it. Changes (disable, edit in M4) are
 * explicit SQL too, and produce a new value rather than mutating a cached one.
 *
 * <h2>Invariants (mirrored by CHECK constraints in V1)</h2>
 * <ul>
 *   <li>A generated code has the generated shape; a custom alias must not. This keeps the
 *       two namespaces apart, so an alias can never block a code the generator will
 *       produce later.</li>
 *   <li>{@code expiresAt}, when present, is after {@code createdAt}.</li>
 * </ul>
 * The constructor enforces these so a bad value fails where it was made, not later as an
 * opaque constraint-violation exception from the database.
 *
 * <h2>Why timestamps are truncated to microseconds</h2>
 * Postgres {@code TIMESTAMPTZ} stores microseconds, but {@link Instant#now()} can carry
 * nanoseconds. Without truncation, a link read back from the database would not
 * {@code equals()} the link that was written, which breaks cache comparisons and tests.
 *
 * @param code      the path segment after the domain: {@code aZ3kQ9x} or a custom alias
 * @param longUrl   the destination; validated by the caller, length-capped by the database
 * @param ownerId   who created it, or {@code null} for an anonymous link
 * @param createdAt when it was created, from the service's {@code Clock}
 * @param expiresAt when it stops redirecting, or {@code null} for never
 * @param status    whether a person has disabled it
 * @param custom    true for a user-chosen alias, false for a generated code
 */
public record ShortUrl(
        String code,
        String longUrl,
        String ownerId,
        Instant createdAt,
        Instant expiresAt,
        LinkStatus status,
        boolean custom) {

    public ShortUrl {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(longUrl, "longUrl");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(status, "status");

        boolean generatedShape = Base62.isGeneratedShape(code);
        if (!custom && !generatedShape) {
            throw new IllegalArgumentException("generated code must be " + Base62.CODE_LENGTH + " base62 chars: " + code);
        }
        if (custom && generatedShape) {
            throw new IllegalArgumentException("custom alias must not look like a generated code: " + code);
        }

        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        if (expiresAt != null) {
            expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
            if (!expiresAt.isAfter(createdAt)) {
                throw new IllegalArgumentException("expiresAt must be after createdAt");
            }
        }
    }

    /** A new, active link with a generated code. */
    public static ShortUrl generated(String code, String longUrl, String ownerId, Instant now, Instant expiresAt) {
        return new ShortUrl(code, longUrl, ownerId, now, expiresAt, LinkStatus.ACTIVE, false);
    }

    /** A new, active link with a user-chosen alias. */
    public static ShortUrl custom(String alias, String longUrl, String ownerId, Instant now, Instant expiresAt) {
        return new ShortUrl(alias, longUrl, ownerId, now, expiresAt, LinkStatus.ACTIVE, true);
    }

    /**
     * True once {@code now} reaches {@code expiresAt}. The instant of expiry itself counts as
     * expired, so a link set to expire at 12:00 doesn't redirect at 12:00:00.
     *
     * <p>{@code now} is a parameter, not {@code Instant.now()}, so the redirect service can use
     * one injected {@code Clock} and tests can freeze time.
     */
    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** True if the link should redirect right now: active and not expired. */
    public boolean isRedirectableAt(Instant now) {
        return status == LinkStatus.ACTIVE && !isExpiredAt(now);
    }
}
