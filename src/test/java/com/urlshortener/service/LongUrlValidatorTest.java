package com.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.service.InvalidLinkRequestException.Reason;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LongUrlValidatorTest {

    private final LongUrlValidator validator = new LongUrlValidator(Set.of("sho.rt"));

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.example.com/products/shoes/running?utm_source=newsletter&ref=8812",
            "http://example.com",
            "HTTPS://Example.com/CaseKept",       // scheme is case-insensitive; path case is kept
            "https://example.com:8443/a#section",
            "https://xn--bcher-kva.example/",     // punycode (IDN) host
            "http://localhost:3000/dev",          // only affects the clicker; not an SSRF risk for us
    })
    void acceptsNormalWebUrls(String url) {
        assertThat(validator.validate(url)).isEqualTo(url);
    }

    @Test
    void trimsSurroundingWhitespaceButOtherwiseStoresTheUrlAsGiven() {
        assertThat(validator.validate("  https://example.com/Path?Q=1 \n")).isEqualTo("https://example.com/Path?Q=1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(document.cookie)",  // runs script on click
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "ftp://example.com/file",
            "example.com/no-scheme",
            "//example.com/protocol-relative",
            "https:///no-host",
            "https://exa mple.com/space-in-host",
            "https://example.com/unencoded space",
    })
    void rejectsNonWebOrMalformedUrls(String url) {
        assertReason(url, Reason.INVALID_URL);
    }

    @Test
    void rejectsBlankAndOverlongUrls() {
        assertReason(null, Reason.INVALID_URL);
        assertReason("   ", Reason.INVALID_URL);
        assertReason("https://example.com/" + "a".repeat(LongUrlValidator.MAX_LENGTH), Reason.INVALID_URL);
    }

    @Test
    void acceptsExactlyTheMaximumLength() {
        String prefix = "https://example.com/";
        String url = prefix + "a".repeat(LongUrlValidator.MAX_LENGTH - prefix.length());
        assertThat(validator.validate(url)).hasSize(LongUrlValidator.MAX_LENGTH);
    }

    @Test
    void rejectsCredentialsDisguise() {
        // Looks like paypal.com, actually goes to evil.example with username "paypal.com".
        assertReason("https://paypal.com@evil.example/login", Reason.URL_NOT_ALLOWED);
        assertReason("https://user:pass@example.com/", Reason.URL_NOT_ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://sho.rt/aZ3kQ9x",
            "http://SHO.RT/aZ3kQ9x",       // case
            "https://sho.rt./aZ3kQ9x",     // trailing dot: same host to DNS
            "https://api.sho.rt/v1/urls",  // subdomain
    })
    void rejectsOurOwnDomainInEveryDisguise(String url) {
        assertReason(url, Reason.URL_NOT_ALLOWED);
    }

    @Test
    void aLookalikeDomainIsNotOurs() {
        // "notsho.rt" ends with "sho.rt" as a string, but isn't a subdomain of it.
        assertThat(validator.validate("https://notsho.rt/x")).isEqualTo("https://notsho.rt/x");
    }

    private void assertReason(String url, Reason reason) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidLinkRequestException.class)
                .satisfies(e -> assertThat(((InvalidLinkRequestException) e).reason()).isEqualTo(reason));
    }
}
