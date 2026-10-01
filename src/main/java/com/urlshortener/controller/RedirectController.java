package com.urlshortener.controller;

import com.urlshortener.service.RedirectResult;
import com.urlshortener.service.RedirectResult.Found;
import com.urlshortener.service.RedirectResult.Gone;
import com.urlshortener.service.RedirectResult.NotFound;
import com.urlshortener.service.RedirectService;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /{code}}: the redirect, and the hottest endpoint in the system.
 *
 * <h2>Response per outcome</h2>
 * <pre>
 * outcome    status  headers                                       why
 * ---------  ------  --------------------------------------------  ------------------------------------
 * Found      302     Location, Cache-Control: private, max-age=90  see below
 * NotFound   404     Cache-Control: no-store                       the code may be created a second later
 * Gone       410     Cache-Control: no-store                       a disabled link may be restored
 * </pre>
 *
 * <h2>302 plus a short Cache-Control, not 301 (notebook §06)</h2>
 * A 301 is cached by browsers, often indefinitely, so repeat clicks never reach us: no click
 * counts, no edits, and a phishing link we take down keeps working for everyone who clicked it
 * once. A 302 keeps every click coming to us. {@code max-age=90} lets the browser skip us
 * for a repeat click within 90 seconds. That's a bounded tradeoff between load and freshness,
 * and a takedown reaches everyone within 90 s. {@code private} keeps shared proxies from
 * caching it, so that CDN caching (M3) is a decision we make, not one a random proxy makes.
 *
 * <h2>Routing: the redirect lives at the root</h2>
 * Every character of the short URL counts, so it's {@code /aZ3kQ9x}, not {@code /r/aZ3kQ9x}.
 * {@code /{code}} matches exactly one path segment, so it never swallows {@code /v1/urls}.
 * {@code GET /v1} does land here, finds no link, and returns 404. That's correct, and it's why
 * custom aliases (M4) must not use reserved words like {@code v1}, {@code api} or
 * {@code actuator}: a link there would shadow a future route.
 *
 * <h2>Why ResponseEntity, not {@code "redirect:"} or RedirectView</h2>
 * The {@code "redirect:"} view name hides the status code and headers, and depending on
 * configuration can append model attributes to the destination's query string. Building the
 * response explicitly keeps every byte we send visible.
 *
 * <p>{@code HEAD} requests (sent by link-preview bots) are served by this same method: Spring
 * maps HEAD onto GET and drops the body.
 */
@RestController
public class RedirectController {

    private static final CacheControl REDIRECT_CACHE = CacheControl.maxAge(java.time.Duration.ofSeconds(90)).cachePrivate();

    private final RedirectService redirectService;

    public RedirectController(RedirectService redirectService) {
        this.redirectService = redirectService;
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        RedirectResult result = redirectService.resolve(code);
        return switch (result) {
            case Found(String longUrl) -> ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(longUrl))
                    .cacheControl(REDIRECT_CACHE)
                    .build();
            case NotFound notFound -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(CacheControl.noStore())
                    .build();
            case Gone gone -> ResponseEntity.status(HttpStatus.GONE)
                    .cacheControl(CacheControl.noStore())
                    .build();
        };
    }
}
