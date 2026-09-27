package com.urlshortener.codegen;

/**
 * Converts a non-negative number into a fixed-width Base62 string and back.
 *
 * <p>This is the last step of every code-generation strategy: whether the number came from a
 * counter, a leased range or a random draw, {@code Base62} turns it into the characters that
 * appear in {@code sho.rt/aZ3kQ9x}. It only encodes. It does not create uniqueness (the
 * generator does) or hide order (the permutation in M2 does).
 *
 * <h2>Why 62 characters</h2>
 * <pre>
 * alphabet         size  URL-safe?                 chars for 3.5T codes
 * ---------------  ----  ------------------------  --------------------
 * base36 [0-9a-z]   36   yes, case-insensitive      8
 * base62 [0-9A-Za-z] 62  yes                        7   ← chosen
 * base64 (+ /)      64   no: '/' splits the path    7
 * base64url (- _)   64   yes, but chat apps trim    7
 *                        a trailing '-'/'_', and '-' is reserved for custom aliases
 * </pre>
 * RFC 3986 makes the URL path case-sensitive, so {@code aZ3} and {@code az3} are different
 * codes. That's what buys 62 symbols instead of 36.
 *
 * <h2>Why a fixed width (zero-padded)</h2>
 * {@code encode(1, 7)} returns {@code "0000001"}, not {@code "1"}.
 * <ul>
 *   <li>Every generated code has exactly one shape (7 chars), so custom aliases can be given a
 *       different shape (8+ chars, or containing {@code '-'}) and can never collide with a
 *       code the generator will produce later.</li>
 *   <li>Length doesn't reveal age. Unpadded, the first 62 links ever made would be 1 char long.</li>
 * </ul>
 *
 * <h2>Why this character order</h2>
 * {@code 0-9A-Za-z} is ASCII order, so for equal-width codes, sorting as strings sorts by
 * number. That's useful for debugging and range checks. But it cuts both ways:
 * <pre>
 * store sorted by key (Bigtable, HBase)  sequential ids → sequential keys → every write hits
 *                                        the same tablet (a hotspot)
 * store hashed by key (DynamoDB, Cassandra)  no hotspot, but codes are still guessable
 * </pre>
 * The keyed permutation applied before encoding (M2) scatters ids, which fixes both problems.
 *
 * <h2>Limits</h2>
 * 62<sup>7</sup> = 3,521,614,606,208 fits easily in a {@code long}. The widest supported
 * width is 10 (62<sup>10</sup> ≈ 8.4 × 10<sup>17</sup>), because 62<sup>11</sup> overflows a
 * {@code long}.
 *
 * <p>Stateless and thread-safe.
 */
public final class Base62 {

    /** Digits, then upper case, then lower case: ASCII order, so string order == numeric order. */
    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    public static final int BASE = ALPHABET.length(); // 62

    /** The width of every generated short code. See the notebook, section 02, for the math. */
    public static final int CODE_LENGTH = 7;

    /** 62^10 is the largest power of 62 that fits in a long. */
    public static final int MAX_WIDTH = 10;

    /** POWERS[w] = 62^w, precomputed so bounds checks don't redo the multiplication. */
    private static final long[] POWERS = new long[MAX_WIDTH + 1];

    /**
     * Reverse lookup: character → digit value, or -1 if the character isn't in the alphabet.
     * An array indexed by char is O(1) and avoids {@code ALPHABET.indexOf}, which scans 62 chars
     * for every character on the redirect path.
     */
    private static final int[] DIGIT_OF = new int[128];

    static {
        POWERS[0] = 1;
        for (int w = 1; w <= MAX_WIDTH; w++) {
            POWERS[w] = POWERS[w - 1] * BASE;
        }
        java.util.Arrays.fill(DIGIT_OF, -1);
        for (int i = 0; i < BASE; i++) {
            DIGIT_OF[ALPHABET.charAt(i)] = i;
        }
    }

    private Base62() {
    }

    /** Encodes {@code value} as a {@value #CODE_LENGTH}-character code. */
    public static String encode(long value) {
        return encode(value, CODE_LENGTH);
    }

    /**
     * Encodes {@code value} as exactly {@code width} characters, left-padded with {@code '0'}.
     *
     * @throws IllegalArgumentException if {@code value} is negative or needs more than
     *     {@code width} characters (value ≥ 62^width). The method throws instead of silently
     *     growing the code, because a wider code would break the "every generated code is
     *     exactly 7 chars" rule.
     */
    public static String encode(long value, int width) {
        checkWidth(width);
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative: " + value);
        }
        if (value >= POWERS[width]) {
            throw new IllegalArgumentException(
                    "value " + value + " needs more than " + width + " base62 chars (max " + (POWERS[width] - 1) + ")");
        }
        // Fill from the right: the least significant digit goes last. Positions never reached
        // keep their initial '0', which is the padding.
        char[] out = new char[width];
        for (int i = width - 1; i >= 0; i--) {
            out[i] = ALPHABET.charAt((int) (value % BASE));
            value /= BASE;
        }
        return new String(out);
    }

    /**
     * Decodes a Base62 string back to its number. Leading {@code '0'}s are just padding, so
     * {@code decode("0000001") == decode("1") == 1}.
     *
     * @throws IllegalArgumentException if the string is empty, longer than {@value #MAX_WIDTH}
     *     chars, or contains a character outside the alphabet. The redirect path should treat
     *     that as a 404, never a 500: anyone can type anything after the slash.
     */
    public static long decode(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("code must not be empty");
        }
        if (code.length() > MAX_WIDTH) {
            throw new IllegalArgumentException("code longer than " + MAX_WIDTH + " chars: " + code.length());
        }
        long value = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            int digit = c < DIGIT_OF.length ? DIGIT_OF[c] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("invalid base62 character '" + c + "' at index " + i);
            }
            value = value * BASE + digit; // at most 10 digits, so this can't overflow a long
        }
        return value;
    }

    /** True if {@code code} has the exact shape of a generated code: 7 chars, all in the alphabet. */
    public static boolean isGeneratedShape(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c >= DIGIT_OF.length || DIGIT_OF[c] < 0) {
                return false;
            }
        }
        return true;
    }

    /** The number of distinct codes of the given width: 62^width. */
    public static long capacity(int width) {
        checkWidth(width);
        return POWERS[width];
    }

    private static void checkWidth(int width) {
        if (width < 1 || width > MAX_WIDTH) {
            throw new IllegalArgumentException("width must be 1.." + MAX_WIDTH + ": " + width);
        }
    }
}
