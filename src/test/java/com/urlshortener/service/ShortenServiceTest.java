package com.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.codegen.ShortCodeGenerator;
import com.urlshortener.domain.LinkStatus;
import com.urlshortener.domain.ShortUrl;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.service.InvalidLinkRequestException.Reason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Unit tests with hand-written fakes instead of mocks: the fake repository has the same
 * insert-if-absent semantics as Postgres (proven in JdbcUrlRepositoryTest), and the scripted
 * generator lets each test decide exactly which attempts collide.
 */
class ShortenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T09:00:00Z");
    private static final String URL = "https://www.example.com/products/shoes/running";

    private final InMemoryUrlRepository repository = new InMemoryUrlRepository();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final LongUrlValidator validator = new LongUrlValidator(Set.of("sho.rt"));

    private ShortenService service(ShortCodeGenerator generator, int maxAttempts) {
        return new ShortenService(repository, generator, validator, clock, maxAttempts);
    }

    @Test
    void createsAnActiveLinkStampedWithTheClock() {
        ShortUrl link = service(ScriptedGenerator.of("aZ3kQ9x"), 8).shorten(URL, "user-42", null);

        assertThat(link.code()).isEqualTo("aZ3kQ9x");
        assertThat(link.longUrl()).isEqualTo(URL);
        assertThat(link.ownerId()).isEqualTo("user-42");
        assertThat(link.createdAt()).isEqualTo(NOW);
        assertThat(link.status()).isEqualTo(LinkStatus.ACTIVE);
        assertThat(link.custom()).isFalse();
        assertThat(repository.findByCode("aZ3kQ9x")).contains(link);
    }

    @Test
    void retriesWithANewCodeWhenTheFirstIsTaken() {
        ShortUrl existing = ShortUrl.generated("aaaaaaa", "https://original.example", null, NOW, null);
        repository.insertIfAbsent(existing);
        ScriptedGenerator generator = ScriptedGenerator.of("aaaaaaa", "bbbbbbb");

        ShortUrl link = service(generator, 8).shorten(URL, null, null);

        assertThat(link.code()).isEqualTo("bbbbbbb");
        assertThat(generator.calls()).isEqualTo(2);
        assertThat(repository.findByCode("aaaaaaa")).contains(existing); // untouched
    }

    @Test
    void givesUpAfterTheRetryBudgetInsteadOfLoopingForever() {
        // A generator stuck on one value: the "something is broken" case.
        repository.insertIfAbsent(ShortUrl.generated("aaaaaaa", "https://original.example", null, NOW, null));
        ScriptedGenerator stuck = ScriptedGenerator.repeating("aaaaaaa");

        assertThatThrownBy(() -> service(stuck, 8).shorten(URL, null, null))
                .isInstanceOf(CodeAllocationException.class)
                .hasMessageContaining("8 attempts");
        assertThat(stuck.calls()).isEqualTo(8);
    }

    @Test
    void storesTheValidatedUrlNotTheRawInput() {
        ShortUrl link = service(ScriptedGenerator.of("aZ3kQ9x"), 8).shorten("  " + URL + "  ", null, null);
        assertThat(link.longUrl()).isEqualTo(URL);
    }

    @Test
    void invalidUrlFailsBeforeAnyCodeIsGenerated() {
        ScriptedGenerator generator = ScriptedGenerator.of("aZ3kQ9x");

        assertThatThrownBy(() -> service(generator, 8).shorten("javascript:alert(1)", null, null))
                .isInstanceOf(InvalidLinkRequestException.class);
        assertThat(generator.calls()).isZero();
        assertThat(repository.size()).isZero();
    }

    @Test
    void expiryMustBeInTheFuture() {
        ShortenService service = service(ScriptedGenerator.repeating("aZ3kQ9x"), 8);

        for (Instant bad : List.of(NOW, NOW.minusSeconds(1))) {
            assertThatThrownBy(() -> service.shorten(URL, null, bad))
                    .isInstanceOf(InvalidLinkRequestException.class)
                    .satisfies(e -> assertThat(((InvalidLinkRequestException) e).reason()).isEqualTo(Reason.INVALID_EXPIRY));
        }

        Instant future = NOW.plus(Duration.ofDays(30));
        assertThat(service.shorten(URL, null, future).expiresAt()).isEqualTo(future);
    }

    @Test
    void rejectsAZeroRetryBudget() {
        assertThatThrownBy(() -> service(ScriptedGenerator.of(), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Same contract as JdbcUrlRepository: putIfAbsent is atomic, and never overwrites. */
    private static final class InMemoryUrlRepository implements UrlRepository {
        private final Map<String, ShortUrl> rows = new ConcurrentHashMap<>();

        @Override
        public boolean insertIfAbsent(ShortUrl url) {
            return rows.putIfAbsent(url.code(), url) == null;
        }

        @Override
        public Optional<ShortUrl> findByCode(String code) {
            return Optional.ofNullable(rows.get(code));
        }

        int size() {
            return rows.size();
        }
    }

    /** {@link #of} returns the given codes in order, once each; {@link #repeating} returns one code forever. */
    private static final class ScriptedGenerator implements ShortCodeGenerator {
        private final Queue<String> codes;
        private final String repeat;
        private int calls;

        private ScriptedGenerator(List<String> codes, String repeat) {
            this.codes = new ArrayDeque<>(codes);
            this.repeat = repeat;
        }

        static ScriptedGenerator of(String... codes) {
            return new ScriptedGenerator(List.of(codes), null);
        }

        static ScriptedGenerator repeating(String code) {
            return new ScriptedGenerator(List.of(), code);
        }

        @Override
        public String next() {
            calls++;
            if (repeat != null) {
                return repeat;
            }
            String code = codes.poll();
            if (code == null) {
                throw new IllegalStateException("script ran out of codes");
            }
            return code;
        }

        int calls() {
            return calls;
        }
    }
}
