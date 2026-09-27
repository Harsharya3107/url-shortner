package com.urlshortener.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Base62Test {

    private static final long CAPACITY_7 = 3_521_614_606_208L; // 62^7

    @Nested
    class Encode {

        @Test
        void zeroIsAllPadding() {
            assertThat(Base62.encode(0)).isEqualTo("0000000");
        }

        @Test
        void digitBoundariesRollOverLikeAnyPositionalSystem() {
            assertThat(Base62.encode(9)).isEqualTo("0000009");
            assertThat(Base62.encode(10)).isEqualTo("000000A");  // first upper-case letter
            assertThat(Base62.encode(35)).isEqualTo("000000Z");
            assertThat(Base62.encode(36)).isEqualTo("000000a");  // first lower-case letter
            assertThat(Base62.encode(61)).isEqualTo("000000z");  // largest single digit
            assertThat(Base62.encode(62)).isEqualTo("0000010");  // carry, like 9 → 10 in decimal
        }

        @Test
        void largestSevenCharValueIsAllZs() {
            assertThat(Base62.encode(CAPACITY_7 - 1)).isEqualTo("zzzzzzz");
        }

        @Test
        void everyCodeIsExactlySevenChars() {
            SplittableRandom rnd = new SplittableRandom(42);
            for (int i = 0; i < 10_000; i++) {
                assertThat(Base62.encode(rnd.nextLong(CAPACITY_7))).hasSize(Base62.CODE_LENGTH);
            }
        }

        @Test
        void refusesToGrowPastTheWidth() {
            // Silently returning 8 chars would break the "generated codes are exactly 7" rule.
            assertThatThrownBy(() -> Base62.encode(CAPACITY_7))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("needs more than 7");
        }

        @Test
        void rejectsNegativeValues() {
            assertThatThrownBy(() -> Base62.encode(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void supportsOtherWidthsUpToTen() {
            assertThat(Base62.encode(62, 2)).isEqualTo("10");
            assertThat(Base62.encode(Base62.capacity(10) - 1, 10)).isEqualTo("zzzzzzzzzz");
            assertThatThrownBy(() -> Base62.encode(1, 11)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Base62.encode(1, 0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Decode {

        @Test
        void roundTripsRandomValues() {
            SplittableRandom rnd = new SplittableRandom(7);
            for (int i = 0; i < 100_000; i++) {
                long value = rnd.nextLong(CAPACITY_7);
                assertThat(Base62.decode(Base62.encode(value))).isEqualTo(value);
            }
        }

        @Test
        void leadingZerosAreOnlyPadding() {
            assertThat(Base62.decode("0000001")).isEqualTo(1);
            assertThat(Base62.decode("1")).isEqualTo(1);
        }

        @Test
        void isCaseSensitive() {
            assertThat(Base62.decode("a")).isEqualTo(36);
            assertThat(Base62.decode("A")).isEqualTo(10);
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc-def", "abc/def", "abc def", "abc+def", "é", "abc_def"})
        void rejectsCharactersOutsideTheAlphabet(String input) {
            // On the redirect path this must become a 404, not a 500.
            assertThatThrownBy(() -> Base62.decode(input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invalid base62 character");
        }

        @Test
        void rejectsEmptyAndTooLong() {
            assertThatThrownBy(() -> Base62.decode("")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Base62.decode(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Base62.decode("12345678901")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void stringOrderMatchesNumericOrder() {
        // True because the alphabet is in ASCII order and codes are fixed-width. This is exactly
        // why raw sequential codes would hotspot a key-sorted store like Bigtable.
        SplittableRandom rnd = new SplittableRandom(99);
        for (int i = 0; i < 10_000; i++) {
            long a = rnd.nextLong(CAPACITY_7);
            long b = rnd.nextLong(CAPACITY_7);
            assertThat(Integer.signum(Base62.encode(a).compareTo(Base62.encode(b))))
                    .isEqualTo(Long.signum(Long.compare(a, b)));
        }
    }

    @Test
    void capacityMatchesTheNotebookMath() {
        assertThat(Base62.capacity(6)).isEqualTo(56_800_235_584L);    // runs out in ~1.5 years
        assertThat(Base62.capacity(7)).isEqualTo(CAPACITY_7);         // ~5% used after 5 years
    }

    @Test
    void generatedShapeIsSevenAlphabetChars() {
        assertThat(Base62.isGeneratedShape("aZ3kQ9x")).isTrue();
        assertThat(Base62.isGeneratedShape("fall-sale")).isFalse(); // custom alias shape
        assertThat(Base62.isGeneratedShape("aZ3kQ9")).isFalse();
        assertThat(Base62.isGeneratedShape("aZ3kQ9x1")).isFalse();
        assertThat(Base62.isGeneratedShape(null)).isFalse();
    }
}
