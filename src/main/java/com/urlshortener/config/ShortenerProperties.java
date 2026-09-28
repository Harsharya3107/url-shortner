package com.urlshortener.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code shortener.*} in application.yml. Validated at startup, so a missing
 * base URL fails the boot instead of the first request.
 *
 * @param baseUrl           prefix for returned short URLs, e.g. {@code https://sho.rt}. Its host
 *                          is automatically refused as a long-URL destination.
 * @param maxInsertAttempts retry budget per create; 8 keeps failures under one a day at 5%
 *                          fill (see {@code ShortenService})
 * @param blockedHosts      other hosts to refuse as destinations (alternate short domains, etc.)
 */
@Validated
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        @NotNull URI baseUrl,
        @DefaultValue("8") @Min(1) @Max(20) int maxInsertAttempts,
        @DefaultValue List<String> blockedHosts) {
}
