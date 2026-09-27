package com.urlshortener.repository;

import com.urlshortener.domain.ShortUrl;
import java.util.Optional;

/**
 * Storage for short links, keyed by code.
 *
 * <p>It's an interface because two implementations will stack. In M3, a caching repository
 * wraps the JDBC one (Decorator pattern): the redirect service calls {@code findByCode} and
 * doesn't know whether the answer came from the in-process cache, Redis or Postgres.
 * <pre>
 * RedirectService → CachingUrlRepository (M3) → JdbcUrlRepository → Postgres
 * ShortenService  ─────────────────────────────→ JdbcUrlRepository
 * </pre>
 *
 * <p>Only two methods for now, because M1 only needs these two access paths. Disable, edit and
 * list-by-owner arrive with the milestones that use them.
 */
public interface UrlRepository {

    /**
     * Stores {@code url} if its code isn't taken, in one atomic step.
     *
     * <p>Returns {@code true} if this call created the row, {@code false} if the code already
     * existed. In that case nothing is written: the existing link is never modified. The
     * caller treats {@code false} as "pick another code" (generated) or "409" (custom alias).
     *
     * <p>Any other constraint violation (bad shape, URL too long) still throws. That's a bug in
     * the caller, not a collision, and must not be retried as one.
     */
    boolean insertIfAbsent(ShortUrl url);

    /** The link for {@code code}, if one exists. Disabled and expired links are returned too; deciding what they mean is the caller's job. */
    Optional<ShortUrl> findByCode(String code);
}
