package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_SCOPE_TABLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a field's rows come from and where the field departs from to reach them, as a captured fact
 * rather than as two derivations that never stated the pair.
 *
 * <p>Captured for real rather than seeded, because two of the three rules turn on a live catalog: a
 * routine terminus resolves a function name against the jOOQ catalog, and a participant fan-out
 * needs real bindings on both members. A seeded row would describe a resolution the catalog never
 * made.
 *
 * <p>One fixture serves every case. The claim is about which rule answers where, so a case that
 * built its own schema would be asserting its own arrangement; here the four coordinates sit in one
 * document and each case reads the row its rule produced, with the scalar fields standing as the
 * control that says the population is fields that reach a table at all.
 */
@PipelineTier
class FieldEndpointsTest {

    @TempDir
    Path tmp;

    private static final String SDL = """
        interface Media { title: String }
        type Language @table(name: "language") { name: String }
        type Film implements Media @table(name: "film") {
          title: String
          language: Language
        }
        type Actor implements Media @table(name: "actor") {
          title: String @field(name: "first_name")
        }
        type Row { title: String }
        type Query {
          films: [Film!]!
          paged: [Film!]! @asConnection
          media: [Media!]!
          rows(actorId: Int!, minLength: Int!): [Row!]!
            @routine(name: "films_for_actor",
                     argMapping: "pActorId: actorId, pMinLength: minLength")
            @defaultOrder(fields: [{name: "film_id"}])
          hopped(actorId: Int!, minLength: Int!): [Film!]!
            @routine(name: "films_for_actor",
                     argMapping: "pActorId: actorId, pMinLength: minLength")
            @reference(path: [{table: "film"}])
            @defaultOrder(primaryKey: true)
        }
        """;

    @Test
    @DisplayName("a field into a bound type departs from its own parent's table")
    void aFieldIntoABoundTypeDepartsFromItsParent() {
        withCaptured(dsl -> assertThat(rows(dsl))
            .as("the departure is the enclosing type's binding, which no relation stated beside"
                + " the arrival before")
            .contains("Film.language  film -> language  NAMED_TYPE_TABLE"));
    }

    @Test
    @DisplayName("a root field departs from nothing, and still arrives somewhere")
    void aRootFieldDepartsFromNothing() {
        withCaptured(dsl -> assertThat(rows(dsl))
            .as("Query binds no table, so there is no enclosing row to depart from; the null is"
                + " the fact and not a gap")
            .contains("Query.films  null -> film  NAMED_TYPE_TABLE"));
    }

    @Test
    @DisplayName("a multi-table container is one row per participant")
    void aMultiTableContainerIsOneRowPerParticipant() {
        withCaptured(dsl -> assertThat(rows(dsl).stream().filter(r -> r.startsWith("Query.media")))
            .as("the interface binds no table of its own, so each bound participant is a target"
                + " and the coordinate is several statements rather than one")
            .containsExactly(
                "Query.media  null -> actor  PARTICIPANT_TABLE",
                "Query.media  null -> film  PARTICIPANT_TABLE"));
    }

    @Test
    @DisplayName("a chain ending on a routine takes the routine's result as its target")
    void aRoutineTerminusIsTheTarget() {
        withCaptured(dsl -> assertThat(rows(dsl))
            .as("the return carries no @table and needs none: the function's result binds it,"
                + " and the name resolved against the catalog where it was written")
            .contains("Query.rows  null -> films_for_actor  ROUTINE_RESULT"));
    }

    @Test
    @DisplayName("a field that reaches no table draws no row")
    void aScalarFieldDrawsNoRow() {
        withCaptured(dsl -> {
            assertThat(rows(dsl))
                .as("an empty relation would satisfy the absence below without meaning it")
                .isNotEmpty();
            assertThat(rows(dsl))
                .as("the population is fields whose rows come from a table; a scalar leaf has none,"
                    + " and the fixture has four of them to make the absence mean something")
                .noneSatisfy(row -> assertThat(row).contains(".title "));
        });
    }

    /**
     * The targets agree with the derivation this replaces, which is what lets its readers move over
     * one at a time. The bases deliberately do not agree everywhere and that is not a mismatch: the
     * routine terminus reaches its table through a type binding there and by naming the function
     * here, so the two relations agree on where the rows come from and disagree on what to call the
     * rule, which is the improvement rather than a drift.
     */
    @Test
    @DisplayName("the targets are exactly the derivation's, whatever each calls the rule")
    void theTargetsAgreeWithTheDerivation() {
        withCaptured(dsl -> {
            var s = INTENT_FIELD_SCOPE_TABLE;
            List<String> derived = dsl
                .select(s.TYPE_NAME, s.FIELD_NAME, s.TABLE_NAME).from(s)
                .where(s.GRAPH_NAME.eq(CapturedStore.GRAPH))
                .fetch(r -> r.value1() + "." + r.value2() + " -> " + r.value3());
            var f = GRAPHITRON_FIELD;
            List<String> captured = dsl
                .select(f.TYPE_NAME, f.FIELD_NAME, f.TO_TABLE).from(f)
                .where(f.GRAPH_NAME.eq(CapturedStore.GRAPH))
                .fetch(r -> r.value1() + "." + r.value2() + " -> " + r.value3());

            assertThat(captured)
                .as("the fixture has to reach some tables, or this compares two empty sets")
                .hasSizeGreaterThanOrEqualTo(5);
            assertThat(captured)
                .as("every target the derivation reports, and no others")
                .containsExactlyInAnyOrderElementsOf(derived);
        });
    }

    /**
     * A field macro expansion minted has endpoints like any other, and this is the case that says
     * the population is what the generator emits rather than what the author wrote. It is also why
     * the relation points at no coordinate relation: a minted field has no coordinate row, so a
     * foreign key there would have excluded exactly the fields a connection is made of.
     */
    @Test
    @DisplayName("a minted connection field has endpoints too")
    void aMintedFieldHasEndpoints() {
        withCaptured(dsl -> assertThat(rows(dsl))
            .as("the connection's nodes field navigates as its element and arrives at that"
                + " element's table")
            .anySatisfy(row -> assertThat(row)
                .startsWith("QueryPagedConnection.nodes")
                .endsWith("-> film  NAMED_TYPE_TABLE")));
    }

    /**
     * The control that says the routine rule knows when not to fire. A hop written after the
     * routine moves the chain's end onto a catalog table, so the return is @table bound and the
     * first rule answers; the routine's own result is a node on the way and not the target. The
     * rule needs no comparison of written positions to know that, which is the point: where a hop
     * follows, the return is bound, and where none does, it is not.
     */
    @Test
    @DisplayName("a hop after the routine puts the target on the catalog table, not the result")
    void aHopAfterTheRoutineMovesTheTarget() {
        withCaptured(dsl -> {
            assertThat(rows(dsl))
                .contains("Query.hopped  null -> film  NAMED_TYPE_TABLE");
            assertThat(rows(dsl))
                .as("the function result is a node of the route, which this relation does not hold")
                .noneSatisfy(row -> assertThat(row)
                    .startsWith("Query.hopped").contains("films_for_actor"));
        });
    }

    private static List<String> rows(DSLContext dsl) {
        var f = GRAPHITRON_FIELD;
        return dsl.select(f.TYPE_NAME, f.FIELD_NAME, f.FROM_TABLE, f.TO_TABLE, f.TARGET_BASIS)
            .from(f).where(f.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .orderBy(f.TYPE_NAME, f.FIELD_NAME, f.TO_TABLE)
            .fetch(r -> r.value1() + "." + r.value2() + "  " + r.value3() + " -> " + r.value4()
                + "  " + r.value5());
    }

    private void withCaptured(Consumer<DSLContext> body) {
        try (var store = CapturedStore.ofCatalog(tmp, SDL, jooq())) {
            body.accept(store.dsl());
        }
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
