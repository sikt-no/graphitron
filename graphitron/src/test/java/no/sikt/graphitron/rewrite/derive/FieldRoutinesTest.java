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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_ROUTINE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which catalog table each {@code @routine} application resolved to, at the chain it stands in.
 *
 * <p>Captured for real rather than seeded, on {@code FieldEndpointsTest}'s terms: the whole claim is
 * that an authored function name met a live catalog, so a seeded row would describe a resolution
 * nothing performed.
 *
 * <p>The fixture is that test's, deliberately, because the pair of coordinates it already carries is
 * exactly the distinction this relation exists for: {@code Query.rows} ends on its routine, and
 * {@code Query.hopped} writes a hop after one so the routine becomes a node on the way. The second
 * is the row nothing held.
 */
@PipelineTier
class FieldRoutinesTest {

    @TempDir
    Path tmp;

    private static final String SDL = """
        type Film @table(name: "film") { title: String }
        type Row { title: String }
        type Query {
          films: [Film!]!
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

    /**
     * Where the routine ends the chain, the chain's target is the result itself, so the row names
     * that table on both sides. Read beside the case below, the pair is the relation's whole point:
     * one authored name, resolved at two coordinates, and what tells the two apart is the chain
     * each stands in rather than anything about the application.
     */
    @Test
    @DisplayName("a routine that ends the chain resolves to its result table")
    void aTerminalRoutineResolves() {
        withCaptured(dsl -> assertThat(rows(dsl))
            .as("the authored name met the catalog where it was written, and the answer is a row"
                + " rather than a fold every reader repeats")
            .contains("Query.rows [films_for_actor] 0 -> films_for_actor"));
    }

    /**
     * The row nothing held. Where a hop follows the routine, the chain's target is the catalog
     * table the hop names, so the endpoint relation records {@code film} and says nothing about the
     * function; its own test asserts that absence in as many words. The application is still an
     * application and still resolves, and until now the only reader that folded the spelling kept
     * one application per field and only where it was the target.
     */
    @Test
    @DisplayName("a routine with a hop after it resolves too, though it is not the chain's target")
    void aMidChainRoutineResolves() {
        withCaptured(dsl -> assertThat(rows(dsl))
            .as("the target is film and the routine is a node on the way; both are facts and this"
                + " relation holds the second")
            .contains("Query.hopped [film] 0 -> films_for_actor"));
    }

    /**
     * The key is the chain and not the field, which is what lets a field's several targets keep
     * their applications apart. Stated here at the shape the fixture has, one target per field: the
     * bracketed target is the chain this application stands in, and a multi-table container would
     * draw the same application once per participant.
     */
    @Test
    @DisplayName("the row names the chain it stands in, not just the field")
    void theRowNamesItsChain() {
        withCaptured(dsl -> assertThat(rows(dsl))
            .allSatisfy(row -> assertThat(row).contains(" [")));
    }

    @Test
    @DisplayName("a field with no routine draws no row")
    void aFieldWithoutARoutineDrawsNothing() {
        withCaptured(dsl -> {
            assertThat(rows(dsl))
                .as("an empty relation would satisfy the absence below without meaning it")
                .isNotEmpty();
            assertThat(rows(dsl))
                .as("the population is @routine applications, and Query.films writes none")
                .noneSatisfy(row -> assertThat(row).startsWith("Query.films "));
        });
    }

    private static List<String> rows(DSLContext dsl) {
        var r = GRAPHITRON_FIELD_ROUTINE;
        return dsl.select(r.TYPE_NAME, r.FIELD_NAME, r.TO_TABLE, r.ORDINAL, r.RESULT_TABLE)
            .from(r).where(r.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .orderBy(r.TYPE_NAME, r.FIELD_NAME, r.TO_TABLE, r.ORDINAL)
            .fetch(r2 -> r2.value1() + "." + r2.value2() + " [" + r2.value3() + "] "
                + r2.value4() + " -> " + r2.value5());
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
