package com.urlshortener.domain;

/**
 * States a person sets on purpose. Stored in {@code short_urls.status}.
 *
 * <p>There is deliberately no {@code EXPIRED} value. Expiry is a fact about time, derived
 * from {@code expires_at} whenever the link is read ({@link ShortUrl#isExpiredAt}). Storing it
 * would need a background job to flip the flag, and the flag would be wrong in the gap
 * between the moment of expiry and the job's next run.
 *
 * <p>There is also no "deleted". A deleted link becomes {@link #DISABLED} and keeps its row,
 * so its code is never handed out again (notebook §10: reusing codes turns old printed links
 * into phishing links).
 */
public enum LinkStatus {
    /** Redirects normally, unless it has expired. */
    ACTIVE,
    /** Taken down by the owner or by trust &amp; safety. Never redirects, never reused. */
    DISABLED
}
