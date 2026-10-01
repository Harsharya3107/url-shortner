package com.urlshortener.controller;

import com.urlshortener.api.CreateUrlRequest;
import com.urlshortener.api.CreateUrlResponse;
import com.urlshortener.config.ShortenerProperties;
import com.urlshortener.domain.ShortUrl;
import com.urlshortener.service.ShortenService;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The management API, under {@code /v1/}. Kept apart from {@link RedirectController} because
 * the two paths differ in traffic by about 100× and will scale and deploy separately
 * (notebook §04). This one is small, authenticated, and allowed to be slower.
 *
 * <h2>Why 201 + Location</h2>
 * {@code 201 Created} says a new resource exists, and {@code Location} says where. Here the
 * short URL itself is that location, since {@code GET} on it is how the link is used. A
 * metadata endpoint ({@code GET /v1/urls/{code}}) arrives with edit and delete in M4.
 *
 * <h2>Who is the owner?</h2>
 * Accounts are out of scope, so the caller passes {@code X-Owner-Id}. In production this would
 * come from the authenticated principal (an API key or token), never from a header a client
 * can set freely. Without it, the link is anonymous.
 *
 * <p>Not idempotent yet: a client that retries after a timeout gets a second link. The fix is
 * an {@code Idempotency-Key} header, as in the notification system. For a shortener a
 * duplicate link is cheap, so it's lower priority than it was there.
 */
@RestController
@RequestMapping("/v1/urls")
public class UrlController {

    private final ShortenService shortenService;
    private final URI baseUrl;

    public UrlController(ShortenService shortenService, ShortenerProperties props) {
        this.shortenService = shortenService;
        this.baseUrl = props.baseUrl();
    }

    @PostMapping
    public ResponseEntity<CreateUrlResponse> create(
            @RequestBody CreateUrlRequest request,
            @RequestHeader(name = "X-Owner-Id", required = false) String ownerId) {

        ShortUrl link = shortenService.shorten(request.longUrl(), ownerId, request.expiresAt());
        URI shortUrl = shortUrlFor(link.code());
        return ResponseEntity.created(shortUrl).body(CreateUrlResponse.of(link, shortUrl));
    }

    /** {@code https://sho.rt} + {@code aZ3kQ9x} → {@code https://sho.rt/aZ3kQ9x}, however many slashes the config has. */
    private URI shortUrlFor(String code) {
        return UriComponentsBuilder.fromUri(baseUrl).pathSegment(code).build().toUri();
    }
}
