package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_BOUND_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_COLUMN_SCOPE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_REFERENCE_STEP_TARGET;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_TYPE_BINDING;
import static no.sikt.graphitron.model.Tables.INTENT_ROUTINE_RETURN_BINDING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The anchor for {@code intent_routine_return_binding} and the reduction over it,
 * {@code intent_resolved_type_binding}: which catalog table stands for a type when the author never
 * wrote {@code @table} on it, and what happens where both populations answer.
 *
 * <p>Every case captures real SDL against the test catalog, on {@code ChainTerminusTest}'s reason:
 * the binding is a resolution against a real catalog reached through a chain walk, and a hand-seeded
 * row would describe a landing the catalog cannot make. The fixtures use {@code films_for_actor},
 * whose result exposes {@code film_id} and {@code title}, so its rows name-match {@code film} and
 * carry a column a child field can resolve.
 *
 * <p>The last two cases are the point of the reduction rather than of either arm: a type bound only
 * by its routine return has to answer for its own children's columns and be a departure a path can
 * leave from, which is what the readers repointed at the reduction now do.
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

    /**
     * Both arms answering the same table is one binding, which is what lets a reader guarding on a
     * single candidate keep working while a schema still carries the redundant {@code @table}. A
     * provenance column on the reduction would make this two rows and break exactly that.
     */
    @Test
    void theTwoArmsAgreeingAreOneResolvedBinding() {
        withCaptured(routineReturning("Row @table(name: \"films_for_actor\")", ""), dsl -> {
            assertThat(derived(dsl)).as("the derivation's own arm").hasSize(1);
            assertThat(directiveArmFor(dsl, "Row")).as("the @table arm").hasSize(1);
            var resolved = resolvedFor(dsl, "Row");
            assertThat(resolved).hasSize(1);
            assertThat(lower(resolved.getFirst().get(INTENT_RESOLVED_TYPE_BINDING.TABLE_NAME)))
                .isEqualTo("films_for_actor");
            assertThat(resolved.getFirst().get(INTENT_RESOLVED_TYPE_BINDING.CANDIDATES)).isEqualTo(1);
        });
    }

    /**
     * The two arms disagreeing is the ambiguity a reader must not have decided for it: the author's
     * {@code @table} names one table and the chain lands on another, and both rows survive.
     */
    @Test
    void theTwoArmsDisagreeingAreTwoRows() {
        withCaptured(routineReturning("Row @table(name: \"film\")", ""), dsl -> {
            var resolved = resolvedFor(dsl, "Row");
            assertThat(resolved.map(r -> lower(r.get(INTENT_RESOLVED_TYPE_BINDING.TABLE_NAME))))
                .containsExactlyInAnyOrder("film", "films_for_actor");
            assertThat(resolved.map(r -> r.get(INTENT_RESOLVED_TYPE_BINDING.CANDIDATES)))
                .containsOnly(2);
        });
    }

    // ===== What the reduction is for =====

    /**
     * The child column read: a scalar field of a type bound only by its routine return resolves in
     * that binding, which before the reduction needed the author to restate the routine as a
     * {@code @table} on the type.
     */
    @Test
    void aChildOfARoutineReturnTypeResolvesItsColumnsThere() {
        withCaptured(routineReturning("Row", ""), dsl -> {
            var scopes = columnScope(dsl, "Row", "title");
            assertThat(scopes).hasSize(1);
            assertThat(scopes.getFirst().get(INTENT_FIELD_COLUMN_SCOPE.BASIS)).isEqualTo("PARENT_BINDING");
            assertThat(lower(scopes.getFirst().get(INTENT_FIELD_COLUMN_SCOPE.TABLE_NAME)))
                .isEqualTo("films_for_actor");
        });
    }

    /**
     * The departure: a {@code @reference} on a child of a routine-return type walks from that
     * binding, so the path leaves the function result by the name-matched arm. Seeding from the
     * {@code @table} population alone would leave this field reaching nothing for precisely the
     * schemas that stop writing the redundant directive.
     */
    @Test
    void aPathDepartsARoutineReturnTypeWithoutTheDirective() {
        withCaptured("""
            type Film @table(name: "film") { title: String }
            type Row {
              title: String
              film: Film @reference(path: [{table: "film"}])
            }
            type Query {
              rows(actorId: Int!, minLength: Int!): [Row!]!
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: actorId, pMinLength: minLength")
            }
            """, dsl -> {
            var targets = dsl.select(INTENT_FIELD_REFERENCE_STEP_TARGET.fields())
                .from(INTENT_FIELD_REFERENCE_STEP_TARGET)
                .where(INTENT_FIELD_REFERENCE_STEP_TARGET.GRAPH_NAME.eq(CapturedStore.GRAPH))
                .fetch();
            assertThat(targets).hasSize(1);
            assertThat(targets.getFirst().get(INTENT_FIELD_REFERENCE_STEP_TARGET.VIA))
                .isEqualTo("NAME_MATCH");
            assertThat(lower(targets.getFirst().get(INTENT_FIELD_REFERENCE_STEP_TARGET.FROM_TABLE)))
                .isEqualTo("films_for_actor");
            assertThat(lower(targets.getFirst().get(INTENT_FIELD_REFERENCE_STEP_TARGET.TO_TABLE)))
                .isEqualTo("film");
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
    private static Result<Record> derived(DSLContext dsl) {
        return dsl.select(INTENT_ROUTINE_RETURN_BINDING.fields())
            .from(INTENT_ROUTINE_RETURN_BINDING)
            .where(INTENT_ROUTINE_RETURN_BINDING.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .orderBy(INTENT_ROUTINE_RETURN_BINDING.TYPE_NAME, INTENT_ROUTINE_RETURN_BINDING.TABLE_NAME)
            .fetch();
    }

    /**
     * The reduction's rows for one type. Scoped, because the fixtures carry {@code @table} types the
     * cases are not about and the reduction holds every binding in the graph.
     */
    private static Result<Record> resolvedFor(DSLContext dsl, String typeName) {
        return dsl.select(INTENT_RESOLVED_TYPE_BINDING.fields())
            .from(INTENT_RESOLVED_TYPE_BINDING)
            .where(INTENT_RESOLVED_TYPE_BINDING.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(INTENT_RESOLVED_TYPE_BINDING.TYPE_NAME.eq(typeName))
            .orderBy(INTENT_RESOLVED_TYPE_BINDING.TABLE_NAME)
            .fetch();
    }

    /** The {@code @table} arm's rows for one type, scoped for the same reason. */
    private static Result<Record> directiveArmFor(DSLContext dsl, String typeName) {
        return dsl.select(INTENT_BOUND_TABLE.fields())
            .from(INTENT_BOUND_TABLE)
            .where(INTENT_BOUND_TABLE.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(INTENT_BOUND_TABLE.TYPE_NAME.eq(typeName))
            .fetch();
    }

    private static Result<Record> columnScope(DSLContext dsl, String typeName, String fieldName) {
        return dsl.select(INTENT_FIELD_COLUMN_SCOPE.fields())
            .from(INTENT_FIELD_COLUMN_SCOPE)
            .where(INTENT_FIELD_COLUMN_SCOPE.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(INTENT_FIELD_COLUMN_SCOPE.TYPE_NAME.eq(typeName))
            .and(INTENT_FIELD_COLUMN_SCOPE.FIELD_NAME.eq(fieldName))
            .fetch();
    }

    private static List<String> typesBoundBy(Result<Record> rows) {
        return rows.map(r -> r.get(INTENT_ROUTINE_RETURN_BINDING.TYPE_NAME));
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private void withCaptured(String sdl, Consumer<DSLContext> body) {
        var ctx = testContext();
        try (var store = CapturedStore.ofCatalog(tmp, sdl,
                new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()))) {
            body.accept(store.dsl());
        }
    }
}
