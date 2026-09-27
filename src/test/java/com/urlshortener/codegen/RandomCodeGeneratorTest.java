package com.urlshortener.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class RandomCodeGeneratorTest {

    @Test
    void everyCodeHasTheGeneratedShape() {
        ShortCodeGenerator generator = new RandomCodeGenerator();
        for (int i = 0; i < 10_000; i++) {
            assertThat(Base62.isGeneratedShape(generator.next())).isTrue();
        }
    }

    @Test
    void sameSeedGivesSameSequence() {
        // Why the constructor takes a RandomGenerator: tests can be exact and reproducible.
        var a = new RandomCodeGenerator(new SplittableRandom(123));
        var b = new RandomCodeGenerator(new SplittableRandom(123));
        for (int i = 0; i < 100; i++) {
            assertThat(a.next()).isEqualTo(b.next());
        }
    }

    @Test
    void firstCharacterIsUniformAcrossTheAlphabet() {
        // If the draw were biased (e.g. abs(nextLong()) % N) or didn't span the full range,
        // some leading characters would be over- or under-represented.
        var generator = new RandomCodeGenerator(new SplittableRandom(2026));
        int draws = 620_000;
        int[] counts = new int[128];
        for (int i = 0; i < draws; i++) {
            counts[generator.next().charAt(0)]++;
        }
        double expected = draws / 62.0; // 10,000 per character
        for (char c : Base62.ALPHABET.toCharArray()) {
            // Standard deviation ≈ √10,000 = 100, so ±500 is a 5-sigma band.
            assertThat((double) counts[c]).as("count of '%s'", c).isBetween(expected - 500, expected + 500);
        }
    }

    @Test
    void noDuplicatesInASmallBatch() {
        // Birthday bound: 100K draws from 3.5T → P(any duplicate) ≈ 100K² / (2 · 3.5T) ≈ 0.14%.
        // Seeded, so this is deterministic, not flaky.
        var generator = new RandomCodeGenerator(new SplittableRandom(1));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            assertThat(seen.add(generator.next())).isTrue();
        }
    }

    @Test
    void isSafeToShareAcrossThreads() throws Exception {
        ShortCodeGenerator generator = new RandomCodeGenerator(); // one instance, like production
        Set<String> codes = ConcurrentHashMap.newKeySet();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 10_000; i++) {
                        String code = generator.next();
                        assertThat(Base62.isGeneratedShape(code)).isTrue();
                        codes.add(code);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(); // rethrows any assertion failure from a worker thread
            }
        }
        assertThat(codes).hasSize(80_000);
    }

    @Test
    void rejectsNullRandom() {
        assertThatThrownBy(() -> new RandomCodeGenerator(null)).isInstanceOf(NullPointerException.class);
    }
}
