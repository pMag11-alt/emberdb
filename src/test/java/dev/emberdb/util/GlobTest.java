package dev.emberdb.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobTest {

    @ParameterizedTest(name = "\"{0}\" vs \"{1}\" -> {2}")
    @CsvSource(delimiter = '|', value = {
            "*            | anything       | true",
            "*            | ''             | true",
            "user:*       | user:42        | true",
            "user:*       | order:42       | false",
            "h?llo        | hello          | true",
            "h?llo        | hllo           | false",
            "h[ae]llo     | hallo          | true",
            "h[ae]llo     | hillo          | false",
            "h[^e]llo     | hallo          | true",
            "h[^e]llo     | hello          | false",
            "h[a-c]llo    | hbllo          | true",
            "h[a-c]llo    | hdllo          | false",
            "*.log        | app.log        | true",
            "*.log        | app.log.1      | false",
            "a*b*c        | aXXbYYc        | true",
            "a*b*c        | aXXbYY         | false",
            "\\*          | *              | true",
            "\\*          | x              | false",
            "**           | x              | true",
            "a*           | a              | true",
    })
    void matches(String pattern, String text, boolean expected) {
        assertEquals(expected, Glob.matches(pattern, text));
    }

    /** Naive backtracking takes exponential time here; the two-pointer algorithm is quadratic at worst. */
    @Test
    @Timeout(1)
    void pathologicalPatternIsFast() {
        String text = "a".repeat(5000);
        assertFalse(Glob.matches("a*a*a*a*a*a*a*a*a*a*b", text));
    }
}
