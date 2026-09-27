package com.urlshortener.codegen;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Picks a uniformly random number in [0, 62<sup>7</sup>) and Base62-encodes it.
 *
 * <p>This is the simplest strategy that works: no allocator, no shared state, and no
 * coordination between servers or regions. Its price is an occasional collision with an
 * existing code, which the caller handles by retrying (see {@link ShortCodeGenerator}).
 *
 * <h2>How often does it collide?</h2>
 * Each new code is checked against the codes <i>already stored</i>, so the chance that one
 * draw hits a taken code is just {@code used / capacity}. That grows linearly, not with the
 * birthday paradox. (The birthday bound is about collisions <i>within</i> a batch of new
 * draws, which the conditional insert also catches.)
 * <pre>
 * codes stored          fraction of 62^7   P(collision)   expected attempts   P(5 misses in a row)
 * --------------------  -----------------  -------------  ------------------  --------------------
 * 1B    (Hello Int.)     0.03%              0.03%          1.0003              ≈ 0
 * 183B  (our 5 years)    5.2%               5.2%           1.055               3.8 × 10⁻⁷
 * 1.76T (half full)      50%                50%            2                   3%
 * </pre>
 * Expected attempts = 1 / (1 − p). Each attempt is a database round trip, so at our scale
 * random costs about 5% extra inserts. Once the keyspace is half full, the counter strategy
 * (M2), which never collides, clearly wins.
 *
 * <h2>Why SecureRandom, not Random</h2>
 * <pre>
 * source             algorithm              predictable from outputs?     thread-safe
 * -----------------  ---------------------  ----------------------------  -----------------
 * java.util.Random   48-bit LCG             yes: a few outputs are enough  yes, but contended
 *                                           to recover its state
 * ThreadLocalRandom  SplitMix-style, fast   yes, not designed to resist    per thread
 * SecureRandom       OS entropy / DRBG      no                            yes
 * </pre>
 * With a predictable generator, an attacker who creates two links can compute the codes the
 * <i>next</i> users will get, and read their links as they're created. The whole point of
 * random codes is that they can't be guessed, so this must be a CSPRNG. It costs about a
 * microsecond per call, which doesn't matter at ~6K writes a second.
 *
 * <h2>Why nextLong(bound), not abs(nextLong()) % N</h2>
 * {@code % N} is biased: 2<sup>63</sup> isn't a multiple of 62<sup>7</sup>, so small
 * remainders come up slightly more often. And {@code Math.abs(Long.MIN_VALUE)} is still
 * negative, a classic bug. {@link RandomGenerator#nextLong(long)} rejects and redraws to stay
 * exactly uniform.
 *
 * <p>Thread-safe as long as the injected {@link RandomGenerator} is ({@link SecureRandom} is).
 */
public final class RandomCodeGenerator implements ShortCodeGenerator {

    private static final long CAPACITY = Base62.capacity(Base62.CODE_LENGTH);

    private final RandomGenerator random;

    /** Production constructor: cryptographically strong randomness. */
    public RandomCodeGenerator() {
        this(new SecureRandom());
    }

    /**
     * Lets tests pass a seeded generator so results are reproducible. Production must use a
     * CSPRNG. See the class docs for why.
     */
    public RandomCodeGenerator(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public String next() {
        return Base62.encode(random.nextLong(CAPACITY));
    }
}
