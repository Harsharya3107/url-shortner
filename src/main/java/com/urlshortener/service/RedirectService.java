package com.urlshortener.service;

import com.urlshortener.domain.LinkStatus;
import com.urlshortener.domain.ShortUrl;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.service.RedirectResult.Found;
import com.urlshortener.service.RedirectResult.Gone;
import com.urlshortener.service.RedirectResult.GoneReason;
import java.time.Clock;
import java.util.Optional;

/**
 * The read path: short code in, redirect decision out.
 * <pre>
 * code ─► plausible shape? ── no ──► NotFound       (no lookup at all)
 *            │ yes
 *            ▼
 *         repository.findByCode ── empty ──► NotFound
 *            │ found
 *            ▼
 *         disabled? ── yes ──► Gone(DISABLED)
 *         expired?  ── yes ──► Gone(EXPIRED)
 *            │
 *            ▼
 *         Found(longUrl)
 * </pre>
 *
 * <h2>Reject impossible codes before the lookup</h2>
 * Anyone can type anything after the slash: {@code /wp-admin.php}, a 5 KB string, emoji.
 * None of that can be a stored code, so there's no reason to spend a database (or, from M3,
 * cache) round trip on it. A code is plausible only if it's 1–32 characters of
 * {@code [0-9A-Za-z-]}: the generated alphabet plus the {@code '-'} that custom aliases may
 * use. It's a cheap filter that runs in memory and keeps scanner traffic off the storage tier.
 *
 * <h2>Where later milestones attach, without changing this class's interface</h2>
 * <ul>
 *   <li><b>M3 caching:</b> the injected {@link UrlRepository} becomes the caching decorator.
 *       The negative cache for misses lives there too, because it's a storage concern.</li>
 *   <li><b>M5 click events:</b> recorded by the controller <i>after</i> it has the response,
 *       never here. Resolving a link must not wait on, or fail because of, analytics.</li>
 * </ul>
 *
 * <h2>Why the checks are ordered disabled-then-expired</h2>
 * Both return 410, but the reason goes to logs and metrics. A taken-down phishing link that
 * also happens to be expired should be counted as a takedown. Checking disabled first gives
 * the more important explanation.
 *
 * <p>Stateless and thread-safe.
 */
public class RedirectService {

    /** Matches the {@code VARCHAR(32)} code column. */
    static final int MAX_CODE_LENGTH = 32;

    private final UrlRepository repository;
    private final Clock clock;

    public RedirectService(UrlRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Decides what {@code GET /{code}} should do. Never throws for bad input: bad input is a {@link RedirectResult.NotFound}. */
    public RedirectResult resolve(String code) {
        if (!isPlausibleCode(code)) {
            return RedirectResult.NOT_FOUND;
        }

        Optional<ShortUrl> found = repository.findByCode(code);
        if (found.isEmpty()) {
            return RedirectResult.NOT_FOUND;
        }

        ShortUrl link = found.get();
        if (link.status() == LinkStatus.DISABLED) {
            return new Gone(GoneReason.DISABLED);
        }
        if (link.isExpiredAt(clock.instant())) {
            return new Gone(GoneReason.EXPIRED);
        }
        return new Found(link.longUrl());
    }

    /**
     * True if {@code code} could be a stored code: 1–32 chars of {@code [0-9A-Za-z-]}. A plain
     * loop instead of a regex: this runs on every request, and it's simple enough to read.
     */
    static boolean isPlausibleCode(String code) {
        if (code == null || code.isEmpty() || code.length() > MAX_CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
