package com.urlshortener.codegen;

/**
 * Produces candidate short codes. This is the Strategy interface, so the approaches from the
 * notebook (section 05) can be swapped and compared in tests without touching the service
 * that uses them.
 *
 * <pre>
 * implementation           unique?                        coordination        milestone
 * -----------------------  -----------------------------  ------------------  ---------
 * RandomCodeGenerator      probably (≈5% retry at 5% full) none               M1
 * RangeLeasingGenerator    yes, by construction            one call per 10K    M2
 * </pre>
 *
 * <h2>Why hashing doesn't fit this interface</h2>
 * {@code next()} takes no input, on purpose. A hash-based generator would need
 * {@code next(longUrl)}, and that dependency is the root of hashing's problems: the same URL
 * always yields the same code, so two owners share one link (and one set of analytics and one
 * expiry), and resolving a collision needs a salt, which throws away the one thing hashing
 * offered (determinism). A code should be an identity for a <i>link</i>, not a fingerprint
 * of a <i>URL</i>. If per-owner dedup is ever wanted, it belongs in a lookup on
 * {@code (owner_id, hash(url))} before generating, not in the generator.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Every returned code has the generated shape: exactly {@link Base62#CODE_LENGTH} Base62
 *       characters ({@link Base62#isGeneratedShape}).</li>
 *   <li>Implementations must be thread-safe. One instance serves every request thread.</li>
 *   <li><b>A returned code is a candidate, not a reservation.</b> The caller must insert it
 *       with a conditional write ({@code INSERT ... ON CONFLICT DO NOTHING}) and, on conflict,
 *       ask for another code.</li>
 * </ul>
 *
 * <h2>Why the database has the final say, even for "guaranteed unique" generators</h2>
 * A generator only knows what it has issued. It can't know about a custom alias a user just
 * took, a code restored from a backup, or a misconfigured range that overlaps another
 * region's. The UNIQUE constraint sees all of them. So the service always uses the same
 * insert-and-retry loop. For the counter generator the loop simply never repeats. That's
 * defense in depth, and it keeps a single code path.
 */
public interface ShortCodeGenerator {

    /** Returns the next candidate code. Never null. */
    String next();
}
