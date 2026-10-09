package com.urlshortener.api;

import com.urlshortener.domain.ShortUrl;
import java.net.URI;
import java.time.Instant;

/**
 * Body of a {@code 201 Created} from {@code POST /v1/urls}.
 *
 * <p>Returns {@code short_url} ready to paste, not just {@code code}. Clients shouldn't need to
 * know the domain or build the URL themselves: that would break the day we move to a new
 * domain or add custom domains ({@code brand.co/sale}).
 */
public record CreateUrlResponse(
        String code,
        URI shortUrl,
        String longUrl,
        Instant createdAt,
        Instant expiresAt) {

    public static CreateUrlResponse of(ShortUrl link, URI shortUrl) {
        return new CreateUrlResponse(link.code(), shortUrl, link.longUrl(), link.createdAt(), link.expiresAt());
    }
}
