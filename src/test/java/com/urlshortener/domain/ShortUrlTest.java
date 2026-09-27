package com.urlshortener.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ShortUrlTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final String URL = "https://www.example.com/products/shoes/running";

    @Nested
    class Namespaces {

        @Test
        void generatedCodeMustHaveTheGeneratedShape() {
            assertThat(ShortUrl.generated("aZ3kQ9x", URL, null, NOW, null).custom()).isFalse();

            assertThatThrownBy(() -> ShortUrl.generated("fall-sale", URL, null, NOW, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("generated code must be 7");
            assertThatThrownBy(() -> ShortUrl.generated("aZ3kQ9", URL, null, NOW, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void customAliasMustNotLookGenerated() {
            // If "sale2026" were 7 chars ("sale202"), the generator could produce it later and
            // the insert would fail for a reason the user never caused.
            assertThat(ShortUrl.custom("fall-sale", URL, "u1", NOW, null).custom()).isTrue();
            assertThat(ShortUrl.custom("sale2026", URL, "u1", NOW, null).custom()).isTrue(); // 8 chars

            assertThatThrownBy(() -> ShortUrl.custom("sale202", URL, "u1", NOW, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not look like a generated code");
        }
    }

    @Nested
    class Expiry {

        @Test
        void noExpiryMeansNeverExpires() {
            ShortUrl link = ShortUrl.generated("aZ3kQ9x", URL, null, NOW, null);
            assertThat(link.isExpiredAt(NOW.plus(Duration.ofDays(365 * 50)))).isFalse();
        }

        @Test
        void theExpiryInstantItselfCountsAsExpired() {
            Instant expires = NOW.plus(Duration.ofHours(1));
            ShortUrl link = ShortUrl.generated("aZ3kQ9x", URL, null, NOW, expires);

            assertThat(link.isExpiredAt(expires.minusNanos(1000))).isFalse();
            assertThat(link.isExpiredAt(expires)).isTrue();
            assertThat(link.isExpiredAt(expires.plusSeconds(1))).isTrue();
        }

        @Test
        void expiryMustBeAfterCreation() {
            assertThatThrownBy(() -> ShortUrl.generated("aZ3kQ9x", URL, null, NOW, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ShortUrl.generated("aZ3kQ9x", URL, null, NOW, NOW.minusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void redirectsOnlyWhenActiveAndNotExpired() {
        Instant expires = NOW.plus(Duration.ofHours(1));
        ShortUrl active = ShortUrl.generated("aZ3kQ9x", URL, null, NOW, expires);
        ShortUrl disabled = new ShortUrl("aZ3kQ9x", URL, null, NOW, expires, LinkStatus.DISABLED, false);

        assertThat(active.isRedirectableAt(NOW)).isTrue();
        assertThat(active.isRedirectableAt(expires)).isFalse();
        assertThat(disabled.isRedirectableAt(NOW)).isFalse();
    }

    @Test
    void timestampsAreTruncatedToWhatPostgresStores() {
        // Postgres keeps microseconds. Without truncation, a round trip through the database
        // would produce a record that isn't equal to the one we wrote.
        Instant withNanos = Instant.parse("2026-09-27T10:00:00.123456789Z");
        ShortUrl link = ShortUrl.generated("aZ3kQ9x", URL, null, withNanos, withNanos.plusSeconds(60));

        assertThat(link.createdAt()).isEqualTo(Instant.parse("2026-09-27T10:00:00.123456Z"));
        assertThat(link.expiresAt()).isEqualTo(Instant.parse("2026-09-27T10:01:00.123456Z"));
        assertThat(link).isEqualTo(ShortUrl.generated("aZ3kQ9x", URL, null,
                Instant.parse("2026-09-27T10:00:00.123456Z"), Instant.parse("2026-09-27T10:01:00.123456Z")));
    }

    @Test
    void requiredFieldsAreRequired() {
        assertThatThrownBy(() -> ShortUrl.generated(null, URL, null, NOW, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShortUrl.generated("aZ3kQ9x", null, null, NOW, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShortUrl.generated("aZ3kQ9x", URL, null, null, null)).isInstanceOf(NullPointerException.class);
    }
}
