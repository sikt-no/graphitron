package no.sikt.graphitron.jakarta.rest;

import jakarta.ws.rs.core.HttpHeaders;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AcceptLanguage} is a pure function of the header value (RFC 9110), so it is a unit test.
 * It sits in the library's own package because {@code graphitron-jakarta-rest} carries no test
 * tree; see {@code OperationGuardTest} for the same arrangement.
 */
@UnitTier
class AcceptLanguageTest {

    @Test
    @DisplayName("Tags come back ordered by quality, most preferred first.")
    void ordersByQuality() {
        assertThat(AcceptLanguage.parse("en;q=0.5, nn, nb;q=0.8")).containsExactly("nn", "nb", "en");
    }

    @Test
    @DisplayName("Equal qualities keep header order, an absent q counting as 1.")
    void headerOrderBreaksTies() {
        assertThat(AcceptLanguage.parse("nb, nn;q=1.0, en")).containsExactly("nb", "nn", "en");
        assertThat(AcceptLanguage.parse("en;q=0.4, nb;q=0.4, nn;q=0.4")).containsExactly("en", "nb", "nn");
    }

    @Test
    @DisplayName("q=0 means not acceptable and is dropped, in any spelling of zero.")
    void dropsQualityZero() {
        assertThat(AcceptLanguage.parse("nb, en;q=0")).containsExactly("nb");
        assertThat(AcceptLanguage.parse("nb, en;q=0.000")).containsExactly("nb");
    }

    @Test
    @DisplayName("The * wildcard names no language and is dropped.")
    void dropsWildcard() {
        assertThat(AcceptLanguage.parse("*")).isEmpty();
        assertThat(AcceptLanguage.parse("nn, *;q=0.1")).containsExactly("nn");
    }

    @Test
    @DisplayName("Whitespace, parameter case and a regional tag are tolerated; the tag is returned as written.")
    void toleratesFormatting() {
        assertThat(AcceptLanguage.parse("  nb-NO ; Q=0.9 ,en-GB;q=0.8")).containsExactly("nb-NO", "en-GB");
    }

    @Test
    @DisplayName("A malformed member is skipped and the rest survive: empty tags, a q that is not a number, a q outside 0 to 1.")
    void skipsMalformedMembers() {
        assertThat(AcceptLanguage.parse("nb, , en;q=abc, de;q=1.5, fr;q=-1, nn;q=0.2")).containsExactly("nb", "nn");
    }

    @Test
    @DisplayName("An absent or blank header yields no preference, not an error.")
    void absentIsEmpty() {
        assertThat(AcceptLanguage.parse((String) null)).isEmpty();
        assertThat(AcceptLanguage.parse("")).isEmpty();
        assertThat(AcceptLanguage.parse("  ")).isEmpty();
        assertThat(AcceptLanguage.parse(headers())).isEmpty();
        assertThat(AcceptLanguage.parse((HttpHeaders) null)).isEmpty();
    }

    @Test
    @DisplayName("Several Accept-Language header lines read as one comma-separated list, in the order sent.")
    void joinsRepeatedHeaderLines() {
        assertThat(AcceptLanguage.parse(headers("nb;q=0.5", "nn"))).containsExactly("nn", "nb");
    }

    /** A request's headers carrying the given {@code Accept-Language} values, and nothing else. */
    private static HttpHeaders headers(String... acceptLanguage) {
        return (HttpHeaders) Proxy.newProxyInstance(HttpHeaders.class.getClassLoader(),
            new Class<?>[] {HttpHeaders.class}, (proxy, method, args) -> {
                if (method.getName().equals("getRequestHeader")
                    && HttpHeaders.ACCEPT_LANGUAGE.equals(args[0])) {
                    return acceptLanguage.length == 0 ? List.of() : List.of(acceptLanguage);
                }
                throw new UnsupportedOperationException(method.getName());
            });
    }
}
