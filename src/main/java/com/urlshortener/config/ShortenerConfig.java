package com.urlshortener.config;

import com.urlshortener.codegen.RandomCodeGenerator;
import com.urlshortener.codegen.ShortCodeGenerator;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.service.LongUrlValidator;
import com.urlshortener.service.RedirectService;
import com.urlshortener.service.ShortenService;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the service layer. The service classes carry no Spring annotations, so their unit
 * tests construct them directly with fakes, and this is the one place that decides which
 * generator and clock production uses.
 */
@Configuration
@EnableConfigurationProperties(ShortenerProperties.class)
public class ShortenerConfig {

    /** UTC system clock. Tests replace it with {@code Clock.fixed(...)}. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** M1 strategy. M2 swaps this one bean for the range-leasing generator. */
    @Bean
    ShortCodeGenerator shortCodeGenerator() {
        return new RandomCodeGenerator();
    }

    @Bean
    LongUrlValidator longUrlValidator(ShortenerProperties props) {
        Set<String> blocked = new HashSet<>(props.blockedHosts());
        blocked.add(props.baseUrl().getHost()); // never shorten our own short links
        return new LongUrlValidator(blocked);
    }

    /** From M3, the repository injected here becomes the caching decorator; this class doesn't change. */
    @Bean
    RedirectService redirectService(UrlRepository repository, Clock clock) {
        return new RedirectService(repository, clock);
    }

    @Bean
    ShortenService shortenService(UrlRepository repository, ShortCodeGenerator generator,
            LongUrlValidator validator, Clock clock, ShortenerProperties props) {
        return new ShortenService(repository, generator, validator, clock, props.maxInsertAttempts());
    }
}
