package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterSpec;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path tmp;

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
    void dotPathArgMapping_liftsOneRootParameterisedDescentHelperPerBinding() {
        // The seam the two ArgumentValueSource forks depend on: the descent is a helper method
        // that takes the root map as an Object *parameter*, so the env fork and the
        // SelectedField fork can each pass their own root. A helper that read its root itself
        // would only ever serve one of them.
        //
        // Asserted off the MethodSpec, never off rendered code strings: whether the body is a
        // statement sequence or a ternary chain is not observable here and is not this tier's
        // business. Behaviour on both forks is proven by the execution tests on
        // Mutation.rentFilmPayloadNested and Actor.filmsNested; that the helpers actually drain
        // onto the class is proven by the sakila example compiling the emitted source, where a
        // dropped drain is a dangling reference.
        var helpers = fetchersClass(NESTED_SDL).methodSpecs().stream()
            .filter(m -> m.name().startsWith("argInput"))
            .toList();

        assertThat(helpers)
            .as("one descent helper per dot-path binding, named for the path it walks")
            .extracting(no.sikt.graphitron.javapoet.MethodSpec::name)
            .containsExactlyInAnyOrder("argInputInventoryId", "argInputCustomerId");
        assertThat(helpers).allSatisfy(helper -> {
            assertThat(helper.modifiers()).contains(Modifier.PRIVATE, Modifier.STATIC);
            assertThat(helper.returnType())
                .as("the helper applies the leaf cast, so it returns the routine parameter's type")
                .isEqualTo(ClassName.get(Integer.class));
            assertThat(helper.parameters())
                .as("the root arrives as a parameter, untyped, so either fork can supply it")
                .singleElement()
                .extracting(ParameterSpec::type)
                .isEqualTo(ClassName.get(Object.class));
        });
    }

    private static String fetcherBody() {
        return fetcherBody(SDL);
    }

    private static no.sikt.graphitron.javapoet.TypeSpec fetchersClass(String sdl) {
        return TestSchemaHelper.storeBackedFetchers(tmp, sdl).stream()
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
