package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier pin of the routine-write fetcher's two-step shape — the routine call
 * inside {@code dsl.transactionResult(...)} (the commit boundary), the chain's follow-up SELECT
 * outside it. Without this pin, a regression to a single-statement emission (the routine joined
 * into the response SELECT, as the read chain renders) would compile clean and even pass a
 * happy-path round trip, but defeat the pinned contract: the routine call is the write and
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

    /** The hop-less carrier form: same routine, the payload return instead of the chain. */
    private static final String CARRIER_SDL = """
        type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
            path: [String!]!
            message: String!
        }
        union RentFilmError = DbErr
        type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
        type RentFilmPayload {
          rental: Rental
          errors: [RentFilmError!]
        }
        type Query { rental: Rental }
        type Mutation {
          rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
            @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
        }
        """;

    @Test
    void routineCarrierFetcher_emitsStepOneOnly() {
        // The mirror of the two-step pin above: the carrier fetcher owns step 1 alone (the
        // routine call plus a projection of its own result columns inside one
        // transactionResult), and NO follow-up .select( after the transaction — step 2 belongs
        // to the payload data field's own fetcher. A regression that grows a follow-up SELECT
        // here would put a read inside the mutation fetcher that the two-statements rule
        // assigns to the data field.
        String body = carrierFetcherBody();

        long transactionResultCalls = countMatches(body, Pattern.compile("transactionResult\\("));
        int firstTransactionResult = body.indexOf("transactionResult(");
        int selectAfterTxn = body.indexOf(".select(", body.indexOf(".fetch", firstTransactionResult));
        assertThat(transactionResultCalls)
            .as("the routine carrier wraps step 1 in exactly one transactionResult(...)")
            .isEqualTo(1);
        assertThat(selectAfterTxn)
            .as("no follow-up .select(...) after step 1's fetch — the post-commit re-read is "
                + "the payload data field's, not this fetcher's")
            .isEqualTo(-1);
        long routineInvocations = countMatches(body, Pattern.compile("Routines\\.rentFilm\\("));
        assertThat(routineInvocations)
            .as("the generated Routines convenience method is invoked exactly once")
            .isEqualTo(1);
    }

    @Test
    void routineCarrierFetcher_catchArmCarriesSentinel() {
        // Outcome (a): the routine raised. The catch arm must return the non-null
        // all-null-column sentinel (DSL.using(SQLDialect.DEFAULT).newRecord(...)) so
        // graphql-java traverses into the errors field instead of short-circuiting on a null
        // parent; the data field's null-key SELECT then renders null.
        String body = carrierFetcherBody();
        assertThat(body)
            .contains("dispatchToLocalContext")
            .contains("newRecord(");
    }

    /** The nested form: the routine's IN parameters live inside a wrapper input argument. */
    private static final String NESTED_SDL = """
        type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
        type Query { rental: Rental }
        input RentFilmInput { inventoryId: Int!, customerId: Int! }
        type Mutation {
          rentFilm(input: RentFilmInput!): [Rental!]!
            @routine(name: "rent_film",
                     argMapping: "pInventoryId: input.inventoryId, pCustomerId: input.customerId")
            @reference(path: [{table: "rental"}])
        }
        """;

    @Test
    void dotPathArgMapping_readsThroughAStatementFormHelperOnTheClass() {
        // The descent is a private static helper on the fetcher class taking the root map as a
        // parameter, not an instanceof-Map ternary chain nested inside the Routines call: the
        // development-principles rule bans the chain because a developer cannot breakpoint a
        // ternary arm, and the root is a parameter because the env and SelectedField forks pass
        // different roots. The helper must also actually land on the class: a registered helper
        // that never drained would leave a dangling call and fail only at the consumer's compile.
        var mutationFetchers = fetchersClass(NESTED_SDL);
        String body = methodBody(mutationFetchers, "rentFilm");

        assertThat(body)
            .as("each dot-path binding reads through its helper, rooted on the outer argument")
            .contains("argInputInventoryId(env.getArgument(\"input\"))")
            .contains("argInputCustomerId(env.getArgument(\"input\"))");
        assertThat(body)
            .as("no ternary chain inline at the call site")
            .doesNotContain("instanceof");

        var helper = mutationFetchers.methodSpecs().stream()
            .filter(m -> m.name().equals("argInputInventoryId"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the registered descent helper never drained"
                + " onto the class; emitted methods: " + mutationFetchers.methodSpecs().stream()
                    .map(no.sikt.graphitron.javapoet.MethodSpec::name).toList()));
        assertThat(helper.code().toString())
            .as("statement form: a guarded rebind, an early return, the leaf cast last")
            .contains("if (!(root instanceof")
            .contains("return null")
            .contains("return (java.lang.Integer)");
    }

    private static String fetcherBody() {
        return fetcherBody(SDL);
    }

    private static no.sikt.graphitron.javapoet.TypeSpec fetchersClass(String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        return TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("MutationFetchers"))
            .findFirst()
            .orElseThrow();
    }

    private static String methodBody(no.sikt.graphitron.javapoet.TypeSpec type, String methodName) {
        return type.methodSpecs().stream()
            .filter(m -> m.name().equals(methodName))
            .findFirst()
            .orElseThrow()
            .code()
            .toString();
    }

    private static String carrierFetcherBody() {
        return fetcherBody(CARRIER_SDL);
    }

    private static String fetcherBody(String sdl) {
        return methodBody(fetchersClass(sdl), "rentFilm");
    }

    private static long countMatches(String haystack, Pattern needle) {
        Matcher m = needle.matcher(haystack);
        long n = 0;
        while (m.find()) n++;
        return n;
    }
}
