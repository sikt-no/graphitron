package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Execution-tier proof that a scalar declared with {@code @scalarType} is registered with its
 * coercing behind a bound on the numbers it is handed, on the engine itself and so under any
 * transport: {@code Graphitron.newGraphQL()} is asked directly, with no HTTP handler in front.
 *
 * <p>{@code BigDecimal} is bound to extended-scalars' {@code GraphQLBigDecimal}, whose coercing reads
 * a number from text as readily as from a number. The generated schema wraps it, so a number whose
 * plain decimal form has more than a thousand digits is a coercion error before that coercing parses
 * it, whether it arrives as a number, as text or as a literal, while an ordinary value in any of
 * those spellings still filters. Each refusal runs under a preemptive timeout far below what parsing
 * the refused text costs, so a regression is a failed case rather than a stalled build.
 */
@ExecutionTier
class ScalarNumberBoundExecutionTest {

    private static final Duration PROMPTLY = Duration.ofSeconds(10);
    private static final String REFUSAL = "digits written out in full";
    private static final String BY_VARIABLE =
        "query($f: FilmRentalRangeFilter) { filmsByRentalRateRange(filter: $f) { title } }";

    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();
        graphql = Graphitron.newGraphQL().build();
    }

    private static ExecutionResult byVariable(Object fra) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user")
            .query(BY_VARIABLE)
            .variables(Map.of("f", Map.of("rentalRateRange", Map.of("fra", fra, "til", "3.00"))))
            .build();
        return graphql.execute(input);
    }

    private static ExecutionResult byLiteral(String fra) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user")
            .query("{ filmsByRentalRateRange(filter: {rentalRateRange: {fra: " + fra + ", til: 3.00}}) { title } }")
            .build();
        return graphql.execute(input);
    }

    private static void assertRefused(String what, ExecutionResult result) {
        assertThat(result.getErrors()).as(what).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage()).as(what).contains(REFUSAL);
    }

    @Test
    void aVariablePastTheBoundIsRefusedPromptly_asTextAndAsANumber() {
        List<Object> values = List.of(
            "7".repeat(1_000_000), "-" + "7".repeat(1_000_000), "0." + "7".repeat(1_000_000),
            "7".repeat(1_001), " " + "7".repeat(1_001) + " ",
            "1e999999999", "-1e999999999", "1e-999999999", "1E+1000",
            new BigDecimal("1e999999999"), new BigDecimal("1e-999999999"), BigInteger.ONE.shiftLeft(1_000_000));
        for (Object value : values) {
            String what = value.getClass().getSimpleName() + " " + abbreviated(value);
            assertRefused(what, assertTimeoutPreemptively(PROMPTLY, () -> byVariable(value), what));
        }
    }

    @Test
    void aLiteralPastTheBoundIsRefusedPromptly() {
        for (String literal : List.of(
                "\"" + "7".repeat(1_000_000) + "\"", "\"1e999999999\"", "1e999999999", "1e-999999999",
                "7".repeat(1_001))) {
            String what = "literal " + abbreviated(literal);
            assertRefused(what, assertTimeoutPreemptively(PROMPTLY, () -> byLiteral(literal), what));
        }
    }

    @Test
    void anOrdinaryValueStillFilters_inEverySpelling() {
        for (Object fra : List.of("2.00", new BigDecimal("2.00"), 2, "2e0", "7".repeat(1_000))) {
            ExecutionResult result = byVariable(fra);
            assertThat(result.getErrors()).as(String.valueOf(abbreviated(fra))).isEmpty();
        }
        for (String literal : List.of("\"2.00\"", "2.00", "2", "2e0")) {
            assertThat(byLiteral(literal).getErrors()).as(literal).isEmpty();
        }
    }

    @Test
    void textThatIsNoNumberReachesTheScalarsOwnCoercing() {
        // Digits followed by anything else are text, which the bound passes and the scalar refuses
        // with its own message.
        for (String text : List.of("abc", "7".repeat(5_000) + "x", "1.2.3", "1e", "")) {
            ExecutionResult result = byVariable(text);
            assertThat(result.getErrors()).as(abbreviated(text)).isNotEmpty();
            assertThat(result.getErrors().get(0).getMessage()).as(abbreviated(text)).doesNotContain(REFUSAL);
        }
    }

    private static String abbreviated(Object value) {
        String text = String.valueOf(value);
        return text.length() > 24 ? text.substring(0, 24) + "..." : text;
    }
}
