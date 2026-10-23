package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CatalogInput;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Record3;
import org.jooq.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_BOUND_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN;
import static no.sikt.graphitron.model.Tables.INTENT_ROUTINE_RETURN_BINDING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The anchor for {@code intent_routine_return_binding}: where a {@code @routine} chain lands, keyed
 * by the type it returns. No binding reads it, a {@code @routine} binding no table; the type a path
 * ending on one returns has the function's result as its rows instead.
 *
 * <p>Every case captures real SDL against the test catalog, on {@code PathTerminusTest}'s reason:
 * the binding is a resolution against a real catalog reached through a chain walk, and a hand-seeded
 * row would describe a landing the catalog cannot make. The fixtures use {@code films_for_actor},
 * whose result exposes {@code film_id} and {@code title}, so its rows name-match {@code film} and
 * carry a column a child field can resolve.
 */
@PipelineTier
class RoutineReturnBindingTest {

    @TempDir
    Path tmp;

    // ===== What the derivation binds =====

    /**
     * The ceremony this relation removes: no {@code @table} anywhere on the return type, and the type
     * is bound to the routine's own result all the same.
     */
    @Test
    void aRoutineReturnTypeIsBoundWithoutTheDirective() {
        withCaptured("""
            type Tilgang {
              organisasjonskode: Int
              rollekode: String
            }
            type Query {
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
            }
            """, dsl -> {
            var rows = derived(dsl);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get(INTENT_ROUTINE_RETURN_BINDING.TYPE_NAME))
                .isEqualTo("Tilgang");
            assertThat(lower(rows.getFirst().get(INTENT_ROUTINE_RETURN_BINDING.TABLE_NAME)))
                .isEqualTo("tilganger_for_feidebruker_med_fs_fiktivt_fnr");
            assertThat(rows.getFirst().get(INTENT_ROUTINE_RETURN_BINDING.CANDIDATES)).isEqualTo(1);
            assertThat(dsl.fetchCount(INTENT_BOUND_TABLE))
                .as("the @table population is untouched, there being no @table to read")
                .isZero();
        });
    }

    /**
     * The binding is the chain's terminus and not its routine: a hop out of the function result moves
     * where the chain lands, so it moves what the return type is bound to.
     */
    @Test
    void aHopMovesTheBindingToWhereTheChainLands() {
        withCaptured(routineReturning("Row", "@reference(path: [{table: \"film\"}])"), dsl -> {
            var rows = derived(dsl);
            assertThat(rows).hasSize(1);
            assertThat(lower(rows.getFirst().get(INTENT_ROUTINE_RETURN_BINDING.TABLE_NAME)))
                .isEqualTo("film");
        });
    }

    /** No terminus, no binding: the same silence the chain relation answers with. */
    @Test
    void anUnreachedChainBindsNothing() {
        withCaptured(routineReturning("Row", "@reference(path: [{table: \"actor\"}])"), dsl ->
            assertThat(derived(dsl))
                .as("actor_id is not exposed on the result, so the chain lands nowhere")
                .isEmpty());
    }

    /**
     * A {@code @node} on a routine-returned type resolves no key columns, because it is not table
     * bound: the manual's rule is that {@code @node} only takes effect on a type that also carries
     * {@code @table}, and the key columns are read off the table the type is bound to.
     *
     * <p>This is the case the nodehood family used to answer for and should not have. Its key
     * columns came through the type-to-table binding, which unions a field's chain landing into a
     * type-grain relation, so a routine's result stood in for a written {@code @table} and a node
     * the manual does not admit got a key. Reading the {@code @table} population directly is what
     * closes it, and this is the only fixture in the tree that can tell the two apart.
     */
    @Test
    void aNodeOnARoutineReturnedTypeIsNotTableBoundAndResolvesNoKeyColumns() {
        withCaptured(routineReturning("Row @node",
                "@reference(path: [{table: \"film\"}])"), dsl -> {
            assertThat(lower(derived(dsl).getFirst().get(INTENT_ROUTINE_RETURN_BINDING.TABLE_NAME)))
                .as("the chain lands on a real table with a key, which is what makes this fixture"
                    + " able to falsify: a bare function result has no primary key and no node"
                    + " metadata, so it could never have yielded a key column either way")
                .isEqualTo("film");
            var k = GRAPHITRON_NODE_KEYCOLUMN;
            assertThat(dsl.selectFrom(k)
                    .where(k.GRAPH_NAME.eq(CapturedStore.GRAPH)).and(k.TYPE_NAME.eq("Row")).fetch())
                .as("no @table, so no table boundness, so no key columns to resolve")
                .isEmpty();
        });
    }

    /** A field with no {@code @routine} is not in this relation's population, whatever it returns. */
    @Test
    void aPlainFieldsReturnTypeIsNotBoundHere() {
        withCaptured("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! }
            """, dsl -> assertThat(derived(dsl)).isEmpty());
    }

    // ===== The seat the derivation excludes =====

    /**
     * The payload carrier: a mutation root's {@code @routine} field carrying no {@code @reference}
     * returns a wrapper whose data field re-reads the written rows, so the chain's rows are not what
     * the field returns and no table stands for the wrapper.
     */
    @Test
    void theCarrierSeatIsExcluded() {
        withCaptured("""
            type Film @table(name: "film") { title: String }
            type RentPayload { film: Film }
            type Mutation {
              rent(actorId: Int!, minLength: Int!): RentPayload
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: actorId, pMinLength: minLength")
            }
            type Query { films: [Film] }
            """, dsl -> assertThat(typesBoundBy(derived(dsl))).doesNotContain("RentPayload"));
    }

    /**
     * The boundary of that exclusion, which is the seat and not the operation: a mutation
     * {@code @routine} field that does carry {@code @reference} is the routine write chain, whose
     * return is the rows the chain lands on exactly as a read's is.
     */
    @Test
    void aMutationChainCarryingAReferenceIsNotExcluded() {
        withCaptured("""
            type Film @table(name: "film") { title: String }
            type Row { title: String }
            type Mutation {
              rent(actorId: Int!, minLength: Int!): [Row!]!
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: actorId, pMinLength: minLength")
                @reference(path: [{table: "film"}])
            }
            type Query { films: [Film] }
            """, dsl -> {
            var rows = derived(dsl);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get(INTENT_ROUTINE_RETURN_BINDING.TYPE_NAME)).isEqualTo("Row");
            assertThat(lower(rows.getFirst().get(INTENT_ROUTINE_RETURN_BINDING.TABLE_NAME)))
                .isEqualTo("film");
        });
    }

    // ===== Arity, and where the two populations meet =====

    /**
     * Two fields returning one type off chains that land differently is an ambiguous binding, and the
     * relation says so with rows rather than declining. Nothing here picks one.
     */
    @Test
    void twoChainsLandingDifferentlyAreTwoCandidates() {
        withCaptured("""
            type Film @table(name: "film") { title: String }
            type Row { title: String }
            type Query {
              direct(actorId: Int!, minLength: Int!): [Row!]!
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: actorId, pMinLength: minLength")
              hopped(actorId: Int!, minLength: Int!): [Row!]!
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: actorId, pMinLength: minLength")
                @reference(path: [{table: "film"}])
            }
            """, dsl -> {
            var rows = derived(dsl);
            assertThat(rows.map(r -> lower(r.get(INTENT_ROUTINE_RETURN_BINDING.TABLE_NAME))))
                .containsExactlyInAnyOrder("film", "films_for_actor");
            assertThat(rows.map(r -> r.get(INTENT_ROUTINE_RETURN_BINDING.CANDIDATES)))
                .containsOnly(2);
        });
    }

    // ===== Helpers =====

    /**
     * A root {@code films_for_actor} field returning {@code returnType}, whose declaration the caller
     * writes in full so a case can add {@code @table} to it, plus whatever chain suffix follows the
     * routine.
     */
    private static String routineReturning(String returnType, String chainSuffix) {
        return """
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { firstName: String }
            type %s { title: String }
            type Query {
              rows(actorId: Int!, minLength: Int!): [%s!]!
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: actorId, pMinLength: minLength")
                %s
            }
            """.formatted(returnType, returnType.split(" ")[0], chainSuffix);
    }

    /** Every row the derivation writes for the fixture graph; unscoped, its population being small. */
    private static Result<Record3<String, String, Integer>> derived(DSLContext dsl) {
        return dsl.select(INTENT_ROUTINE_RETURN_BINDING.TYPE_NAME,
            INTENT_ROUTINE_RETURN_BINDING.TABLE_NAME, INTENT_ROUTINE_RETURN_BINDING.CANDIDATES)
            .from(INTENT_ROUTINE_RETURN_BINDING)
            .where(INTENT_ROUTINE_RETURN_BINDING.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .orderBy(INTENT_ROUTINE_RETURN_BINDING.TYPE_NAME, INTENT_ROUTINE_RETURN_BINDING.TABLE_NAME)
            .fetch();
    }

    private static List<String> typesBoundBy(Result<Record3<String, String, Integer>> rows) {
        return rows.map(r -> r.get(INTENT_ROUTINE_RETURN_BINDING.TYPE_NAME));
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private void withCaptured(String sdl, Consumer<DSLContext> body) {
        var ctx = testContext();
        try (var store = CapturedStore.ofCatalog(tmp, sdl,
                CatalogInput.of(ctx))) {
            body.accept(store.dsl());
        }
    }
}
