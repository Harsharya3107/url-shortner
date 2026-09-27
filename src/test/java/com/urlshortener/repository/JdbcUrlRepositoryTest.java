package com.urlshortener.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.domain.LinkStatus;
import com.urlshortener.domain.ShortUrl;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs against the real Postgres test database ({@code urlshortener_test}), because the
 * behavior under test (ON CONFLICT, constraint checks, aborted transactions, concurrent
 * inserts) is exactly what an in-memory fake would get wrong.
 *
 * <p>{@code NOT_SUPPORTED} turns off the usual roll-back-after-each-test transaction: the
 * race tests need every thread's insert committed and visible to the others.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JdbcUrlRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcUrlRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00.123456Z");

    @Autowired
    private UrlRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void cleanTable() {
        jdbc.sql("TRUNCATE short_urls").update();
    }

    private static ShortUrl link(String code, String url) {
        return ShortUrl.generated(code, url, null, NOW, null);
    }

    @Nested
    class InsertAndFind {

        @Test
        void roundTripsEveryField() {
            ShortUrl original = ShortUrl.custom("fall-sale", "https://example.com/sale", "user-42",
                    NOW, NOW.plus(Duration.ofDays(30)));

            assertThat(repository.insertIfAbsent(original)).isTrue();
            assertThat(repository.findByCode("fall-sale")).contains(original);
        }

        @Test
        void roundTripsNullOwnerAndNoExpiry() {
            ShortUrl original = link("aZ3kQ9x", "https://example.com");
            repository.insertIfAbsent(original);

            ShortUrl found = repository.findByCode("aZ3kQ9x").orElseThrow();
            assertThat(found).isEqualTo(original);
            assertThat(found.ownerId()).isNull();
            assertThat(found.expiresAt()).isNull();
            assertThat(found.status()).isEqualTo(LinkStatus.ACTIVE);
        }

        @Test
        void missingCodeIsEmpty() {
            assertThat(repository.findByCode("nope123")).isEmpty();
        }

        @Test
        void lookupIsCaseSensitive() {
            repository.insertIfAbsent(link("aZ3kQ9x", "https://example.com"));
            assertThat(repository.findByCode("az3kq9x")).isEmpty();
        }
    }

    @Nested
    class Collisions {

        @Test
        void takenCodeReturnsFalseAndLeavesTheOriginalUntouched() {
            // The bug JPA's save() would have: the second insert overwriting the first link.
            repository.insertIfAbsent(link("aZ3kQ9x", "https://original.example"));

            assertThat(repository.insertIfAbsent(link("aZ3kQ9x", "https://attacker.example"))).isFalse();
            assertThat(repository.findByCode("aZ3kQ9x").orElseThrow().longUrl()).isEqualTo("https://original.example");
        }

        @Test
        void otherConstraintViolationsStillThrow() {
            // A URL over 2048 chars is a caller bug. Reporting it as "code taken" would make
            // the service retry with a new code forever.
            ShortUrl tooLong = link("aZ3kQ9x", "https://x.com/" + "a".repeat(2040));
            assertThatThrownBy(() -> repository.insertIfAbsent(tooLong))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_long_url_length");
        }

        @Test
        void aCollisionInsideATransactionDoesNotPoisonIt() {
            // With a plain INSERT, the duplicate would abort the transaction (SQLSTATE 25P02)
            // and the retry below would fail. ON CONFLICT DO NOTHING leaves it usable.
            repository.insertIfAbsent(link("aZ3kQ9x", "https://original.example"));

            Boolean retried = new TransactionTemplate(txManager).execute(status -> {
                boolean first = repository.insertIfAbsent(link("aZ3kQ9x", "https://second.example"));
                boolean retry = repository.insertIfAbsent(link("bQ7mP2c", "https://second.example"));
                return !first && retry;
            });

            assertThat(retried).isTrue();
            assertThat(repository.findByCode("bQ7mP2c")).isPresent();
        }

        @Test
        void aPlainInsertCollisionDoesPoisonTheTransaction() {
            // The counter-example, so the claim above isn't taken on faith.
            repository.insertIfAbsent(link("aZ3kQ9x", "https://original.example"));

            assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
                try {
                    jdbc.sql("INSERT INTO short_urls (code, long_url, created_at) VALUES ('aZ3kQ9x', 'https://x.example', now())")
                            .update();
                } catch (DuplicateKeyException expected) {
                    // "handle" the collision and retry, the way catch-based code would
                }
                jdbc.sql("INSERT INTO short_urls (code, long_url, created_at) VALUES ('bQ7mP2c', 'https://x.example', now())")
                        .update();
            })).hasMessageContaining("current transaction is aborted");
        }
    }

    @Nested
    class Races {

        private static final int THREADS = 16;

        @Test
        void checkThenInsertLetsEveryRacerBelieveTheCodeIsFree() throws Exception {
            // Forces the interleaving from the JdbcUrlRepository Javadoc: every thread runs its
            // SELECT, waits at the barrier, then inserts. Every one of them saw "free".
            CyclicBarrier afterCheck = new CyclicBarrier(THREADS);
            AtomicInteger sawFree = new AtomicInteger();
            AtomicInteger inserted = new AtomicInteger();
            AtomicInteger duplicateErrors = new AtomicInteger();

            runConcurrently(i -> {
                boolean exists = repository.findByCode("aZ3kQ9x").isPresent();
                afterCheck.await();
                if (!exists) {
                    sawFree.incrementAndGet();
                    try {
                        jdbc.sql("INSERT INTO short_urls (code, long_url, created_at) VALUES ('aZ3kQ9x', :url, now())")
                                .param("url", "https://racer-" + i + ".example")
                                .update();
                        inserted.incrementAndGet();
                    } catch (DuplicateKeyException e) {
                        duplicateErrors.incrementAndGet();
                    }
                }
            });

            assertThat(sawFree).hasValue(THREADS);                // the check told all 16 "go ahead"
            assertThat(inserted).hasValue(1);                     // only the PK saved us
            assertThat(duplicateErrors).hasValue(THREADS - 1);    // 15 surprise exceptions
        }

        @Test
        void insertIfAbsentHasExactlyOneWinnerAndItsUrlIsTheOneStored() throws Exception {
            CyclicBarrier start = new CyclicBarrier(THREADS);
            AtomicInteger winners = new AtomicInteger();
            String[] winningUrl = new String[1];

            runConcurrently(i -> {
                String url = "https://racer-" + i + ".example";
                start.await(); // release all threads at once to maximize contention
                if (repository.insertIfAbsent(link("aZ3kQ9x", url))) {
                    winners.incrementAndGet();
                    winningUrl[0] = url;
                }
            });

            assertThat(winners).hasValue(1);
            assertThat(repository.findByCode("aZ3kQ9x").orElseThrow().longUrl()).isEqualTo(winningUrl[0]);
        }

        private void runConcurrently(Racer racer) throws Exception {
            try (var pool = Executors.newFixedThreadPool(THREADS)) {
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < THREADS; t++) {
                    int i = t;
                    futures.add(pool.submit(() -> {
                        racer.run(i);
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(); // surfaces any exception thrown inside a racer
                }
            }
        }
    }

    @FunctionalInterface
    private interface Racer {
        void run(int index) throws Exception;
    }
}
