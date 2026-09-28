package com.urlshortener.service;

/**
 * What {@link RedirectService#resolve} decided. The controller maps each case to one HTTP
 * response, and a {@code switch} over this sealed type won't compile if a case is missed.
 * <pre>
 * result     HTTP                          meaning
 * ---------  ----------------------------  -----------------------------------------------
 * Found      302 + Location                redirect the browser
 * NotFound   404 Not Found                 no such code (typo, scanner, malformed input)
 * Gone       410 Gone                      it existed, but expired or was taken down
 * </pre>
 *
 * <h2>Why a result type, not exceptions</h2>
 * This is the hottest path in the system (~600K requests a second at peak), and misses are
 * normal traffic: typos, bots trying random codes, old links. Exceptions for expected
 * outcomes cost a stack trace each, clutter error monitoring, and hide control flow. A
 * result type makes every outcome visible in the method signature.
 *
 * <h2>Why 410 is different from 404</h2>
 * 410 tells crawlers the link is permanently gone, so search engines drop it instead of
 * retrying, and it lets support tell "you mistyped it" apart from "it expired". The small
 * cost: it reveals that a code once existed. That's acceptable, since the code was already
 * public wherever it was shared.
 */
public sealed interface RedirectResult {

    /** Redirect to {@code longUrl}. */
    record Found(String longUrl) implements RedirectResult {
    }

    /** No link with that code, or the code couldn't possibly be one. */
    record NotFound() implements RedirectResult {
    }

    /** The link exists but must not redirect. {@code reason} is for logs and metrics, not the response body. */
    record Gone(GoneReason reason) implements RedirectResult {
    }

    enum GoneReason {
        EXPIRED,
        /** By the owner, or by trust &amp; safety. The same 410 either way, so the response doesn't reveal a takedown. */
        DISABLED
    }

    /** Shared instance: NotFound carries no data, and this path allocates on every miss otherwise. */
    RedirectResult NOT_FOUND = new NotFound();
}
