package dev.emberdb.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NumbersTest {

    @Test
    void parsesIntegers() {
        assertEquals(0L, Numbers.parseLong("0"));
        assertEquals(-42L, Numbers.parseLong("-42"));
        assertEquals(Long.MAX_VALUE, Numbers.parseLong("9223372036854775807"));
        assertEquals(Long.MIN_VALUE, Numbers.parseLong("-9223372036854775808"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-", "+1", "01", "-0", " 1", "1 ", "1.0", "9223372036854775808", "abc"})
    void rejectsWhatRedisRejects(String input) {
        assertNull(Numbers.parseLong(input));
    }

    @Test
    void parsesDoubles() {
        assertEquals(1.5, Numbers.parseDouble("1.5"));
        assertEquals(-0.25, Numbers.parseDouble("-.25"));
        assertEquals(1e10, Numbers.parseDouble("1e10"));
        assertEquals(Double.POSITIVE_INFINITY, Numbers.parseDouble("+inf"));
        assertEquals(Double.NEGATIVE_INFINITY, Numbers.parseDouble("-INF"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "nan", "NaN", "1d", "0x10", " 1", "1..2", "e5"})
    void rejectsInvalidDoubles(String input) {
        assertNull(Numbers.parseDouble(input));
    }

    @Test
    void formatsLikeRedis() {
        assertEquals("3", Numbers.formatDouble(3.0));
        assertEquals("1.5", Numbers.formatDouble(1.5));
        assertEquals("0.1", Numbers.formatDouble(0.1));
        assertEquals("-2.75", Numbers.formatDouble(-2.75));
        assertEquals("inf", Numbers.formatDouble(Double.POSITIVE_INFINITY));
    }

    @Test
    void formattingRoundTrips() {
        double[] values = {0.1, 1 / 3.0, 123456.789, 1e-7, 2.5e25, -1e300, 1e17 + 2};
        for (double value : values) {
            assertEquals(value, Numbers.parseDouble(Numbers.formatDouble(value)));
        }
    }
}
