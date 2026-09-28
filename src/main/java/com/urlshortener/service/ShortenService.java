package com.urlshortener.service;

import com.urlshortener.codegen.ShortCodeGenerator;
import com.urlshortener.domain.ShortUrl;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.service.InvalidLinkRequestException.Reason;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates short links: validate, generate a candidate code, insert it atomically, and retry
 * with a new code if it was taken.
 * <pre>
 * validate URL and expiry ─► loop up to maxAttempts:
 *                               code = generator.next()
 *                               insertIfAbsent(link) ── true ──► return link
 *                                        │ false (code taken)
 *                                        └─► try the next code
 *                            all attempts taken ──► CodeAllocationException (5xx, logged)
 * </pre>
 *
 * <h2>Sizing the retry budget from the math, not by feel</h2>
 * Each attempt collides with probability p = (codes stored) / 62⁷, independently, so all k
 * attempts fail with probability p<sup>k</sup>. The budget should keep failed requests per
 * day, {@code daily_volume × p^k}, well under one:
 * <pre>
 * p (fill)             k=3        k=5          k=8
 * -------------------  ---------  -----------  --------------
 * 0.03% (1B links)     ≈ 0        ≈ 0          ≈ 0
 * 5.2%  (year 5)       14,000/day  38/day       0.005/day   ← 8 chosen
 * 50%   (half full)    12.5M/day   3.1M/day     390K/day    → switch to the counter
 * </pre>
 * (daily volume = 100M.) Past that point the budget can't fix things: random codes stop being
 * the right strategy, which is the M2 story. Hitting the limit before then means a bug, so it
 * throws and logs instead of looping forever.
 *
 * <h2>Why there's no @Transactional here</h2>
 * Each attempt is one atomic statement that either creates the row or does nothing. A
 * surrounding transaction would add nothing to correctness, but it would hold a database
 * connection across all attempts and, for a collision with a plain INSERT, poison the
 * transaction (see {@code JdbcUrlRepository}).
 *
 * <h2>Why a Clock is injected</h2>
 * {@code createdAt} and the "expiry must be in the future" check both need "now". Taking it
 * from one injected {@link Clock} makes both consistent within a request, and lets tests pin
 * time exactly instead of sleeping or asserting "roughly now".
 */
public class ShortenService {

    private static final Logger log = LoggerFactory.getLogger(ShortenService.class);

    private final UrlRepository repository;
    private final ShortCodeGenerator generator;
    private final LongUrlValidator urlValidator;
    private final Clock clock;
    private final int maxAttempts;

    public ShortenService(UrlRepository repository, ShortCodeGenerator generator, LongUrlValidator urlValidator,
            Clock clock, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1: " + maxAttempts);
        }
        this.repository = repository;
        this.generator = generator;
        this.urlValidator = urlValidator;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Creates a short link with a generated code.
     *
     * @param longUrl   where the link should go
     * @param ownerId   who's creating it, or {@code null} for anonymous
     * @param expiresAt when it should stop working, or {@code null} for never
     * @throws InvalidLinkRequestException if the URL or expiry is unacceptable (the caller's fault)
     * @throws CodeAllocationException     if every attempt collided (our fault)
     */
    public ShortUrl shorten(String longUrl, String ownerId, Instant expiresAt) {
        String url = urlValidator.validate(longUrl);
        Instant now = clock.instant();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new InvalidLinkRequestException(Reason.INVALID_EXPIRY, "expires_at must be in the future");
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ShortUrl candidate = ShortUrl.generated(generator.next(), url, ownerId, now, expiresAt);
            if (repository.insertIfAbsent(candidate)) {
                if (attempt > 1) {
                    // Expected occasionally with random codes. A rising rate is the signal that
                    // the keyspace is filling up (M6 turns this into a metric).
                    log.debug("code allocated after {} attempts", attempt);
                }
                return candidate;
            }
        }

        log.error("no free code after {} attempts; generator or keyspace is broken", maxAttempts);
        throw new CodeAllocationException("could not allocate a short code after " + maxAttempts + " attempts");
    }
}
