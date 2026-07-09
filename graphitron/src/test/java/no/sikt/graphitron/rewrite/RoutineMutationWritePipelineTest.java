package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R451: pipeline-tier pin of the routine-write fetcher's two-step shape — the routine call
 * inside {@code dsl.transactionResult(...)} (the commit boundary), the chain's follow-up SELECT
 * outside it. Without this pin, a regression to a single-statement emission (the routine joined
 * into the response SELECT, as the R435 read chain renders) would compile clean and even pass a
 * happy-path round trip, but defeat the item's pinned contract: the routine call is the write and
 * commits before the follow-up query, so the response always observes committed state.
 *
 * <p>Like {@code SingleRecordPayloadPipelineTest.directReturn_dmlFetcher_emitsTwoStepShape}, the
 * pin operates on the rendered body as a call-site fingerprint (counts and ordering of jOOQ DSL
 * method names), never an exact source-text match.
 */
@PipelineTier
class RoutineMutationWritePipelineTest {

    private static final String SDL = """
        type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
        type Query { rental: Rental }
        type Mutation {
          rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
            @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            @reference(path: [{table: "rental"}])
        }
        """;

    @Test
    void routineWriteFetcher_emitsTwoStepShape() {
        String body = fetcherBody();

        long transactionResultCalls = countMatches(body, Pattern.compile("transactionResult\\("));
        int firstTransactionResult = body.indexOf("transactionResult(");
        int routineCall = body.indexOf("rentFilm(");
        int selectAfterTxn = firstTransactionResult < 0
            ? -1
            : body.indexOf(".select(", body.indexOf(".fetch", firstTransactionResult));
        assertThat(transactionResultCalls)
            .as("the routine write wraps step 1 in exactly one transactionResult(...) — the R429 "
                + "per-mutation-field commit boundary")
            .isEqualTo(1);
        assertThat(routineCall)
            .as("the routine call (Routines.rentFilm) is emitted before the transaction boundary "
                + "as the declared chain start")
            .isLessThan(firstTransactionResult);
        assertThat(selectAfterTxn)
            .as("the chain's follow-up .select(...) runs after step 1's fetch inside the "
                + "transactionResult call site — the post-commit re-read")
            .isGreaterThan(firstTransactionResult);
    }

    @Test
    void routineWriteFetcher_executesRoutineExactlyOnce() {
        String body = fetcherBody();
        // The routine must never appear in step 2's FROM: re-invoking it would re-execute the
        // write. One convenience-method invocation total (field references off the declared
        // `source` local are reads of the captured key columns, not re-invocations).
        long routineInvocations = countMatches(body, Pattern.compile("Routines\\.rentFilm\\("));
        assertThat(routineInvocations)
            .as("the generated Routines convenience method is invoked exactly once — the write "
                + "executes once, and the post-commit re-read anchors on the hop table")
            .isEqualTo(1);
    }

    private static String fetcherBody() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        var mutationFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("MutationFetchers"))
            .findFirst()
            .orElseThrow();
        return mutationFetchers.methodSpecs().stream()
            .filter(m -> m.name().equals("rentFilm"))
            .findFirst()
            .orElseThrow()
            .code()
            .toString();
    }

    private static long countMatches(String haystack, Pattern needle) {
        Matcher m = needle.matcher(haystack);
        long n = 0;
        while (m.find()) n++;
        return n;
    }
}
