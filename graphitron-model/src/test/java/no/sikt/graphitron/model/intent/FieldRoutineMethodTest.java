package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.tables.records.IntentFieldRoutineMethodRecord;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_FIELD_ROUTINE_METHOD;
import static no.sikt.graphitron.model.Tables.INTENT_SPELLED_TABLE;
import static no.sikt.graphitron.model.test.SeededStore.seedCatalogRoutine;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedRoutine;
import static no.sikt.graphitron.model.test.SeededStore.seedRoutineParameter;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a {@code @routine} application resolves to: {@code intent_field_routine_method}, the
 * generated call surface the census holds for the routine the spelling names.
 *
 * <p>The population is stated as rows on both sides, as the producer pair states its own. A spelling
 * is a name an author wrote and the census is a name a generator emitted, so a fixture can hold a
 * spelling matching nothing, one matching a stored table rather than a callable, a callable whose
 * generated model exposes no call surface, and a name two schemas both declare. Those are the four
 * shapes the resolution's answers are made of, and half the cases below assert that a coordinate
 * produces no row: absence is this relation's claim rather than a gap in it, and which of the three
 * causes produced it is a fact a reader has to be able to reach.
 */
class FieldRoutineMethodTest {

    private static final String GRAPH = "graph";
    private static final String APP = "app-jooq";

    private static final String ROUTINES = "app.jooq.Routines";

    // ===== The application resolves =====

    /** The ordinary case: one spelling, one callable, and the call surface it names. */
    @Test
    void anApplicationResolvesToItsCallSurface() {
        withRoutines(dsl -> {
            var rows = rowsAt(dsl, "Query", "films");
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(row.getSourceName()).isEqualTo(APP);
            assertThat(row.getTableSchema()).isEqualTo("public");
            assertThat(row.getRoutineName()).isEqualTo("films_for_actor");
            assertThat(row.getClassName()).isEqualTo(ROUTINES);
            assertThat(row.getMethodName()).isEqualTo("filmsForActor");
            assertThat(row.getParameters()).isEqualTo(2);
            assertThat(row.getCandidates()).isOne();
        });
    }

    /**
     * The arity is the generated method's own, read off the routine's parameter list. A routine that
     * takes none is a row with zero, which is a fact about the call and not a fallback: what
     * separates it from a routine whose call surface the model does not expose is the row existing.
     */
    @Test
    void aRoutineWithNoParametersResolvesWithArityZero() {
        withRoutines(dsl ->
            assertThat(rowsAt(dsl, "Query", "everything"))
                .extracting(IntentFieldRoutineMethodRecord::getParameters)
                .containsExactly(0));
    }

    /**
     * Keyed on the application and not on the field, because {@code @routine} is repeatable: a field
     * carrying two is two rows in the order they were written, which is the order the table chain
     * interleaves them in.
     */
    @Test
    void twoApplicationsOnOneFieldAreTwoRowsInWrittenOrder() {
        withRoutines(dsl ->
            assertThat(rowsAt(dsl, "Query", "chained"))
                .extracting(r -> r.getOrdinal() + ":" + r.getRoutineName())
                .containsExactly("0:films_for_actor", "1:rent_film"));
    }

    // ===== The three causes of absence =====

    /**
     * A spelling that matches no catalog object at all: the spelling view holds nothing for it, so
     * neither does this relation, and the first join is what says which of the two happened.
     */
    @Test
    void aSpellingMatchingNoCatalogObjectResolvesToNothing() {
        withRoutines(dsl -> {
            assertThat(rowsAt(dsl, "Query", "missing")).isEmpty();
            assertThat(spellingsOf(dsl, "no_such_routine"))
                .as("and the name matched no catalog object, which is why")
                .isEmpty();
        });
    }

    /**
     * A spelling that names a stored table. It resolves on the spelling view like any other name and
     * then matches no callable, which is this relation saying "not callable" without restating what
     * {@code sql_table.table_type} already means. The spelling row is asserted present, since that
     * is the whole of what separates this cause from the one above.
     */
    @Test
    void aSpellingNamingAStoredTableResolvesToNothing() {
        withRoutines(dsl -> {
            assertThat(rowsAt(dsl, "Query", "notCallable")).isEmpty();
            assertThat(spellingsOf(dsl, "film"))
                .as("the name matched a catalog object; it is simply not one that can be called")
                .hasSize(1);
        });
    }

    /**
     * A callable whose generated model exposes no call surface. This relation is the call surface, so
     * naming a class that does not exist would be a worse answer than naming nothing.
     */
    @Test
    void aRoutineWithNoGeneratedCallSurfaceResolvesToNothing() {
        withRoutines(dsl -> assertThat(rowsAt(dsl, "Query", "unexposed")).isEmpty());
    }

    // ===== Ambiguity =====

    /**
     * Ambiguity is rows and never a decline, as on the neighbouring resolutions: a name two schemas
     * both declare is two rows and the count says so, leaving the reading to the reader.
     */
    @Test
    void aNameTwoSchemasDeclareIsTwoRowsThatSaySo() {
        withRoutines(dsl -> {
            var rows = rowsAt(dsl, "Query", "ambiguous");
            assertThat(rows).extracting(IntentFieldRoutineMethodRecord::getTableSchema)
                .containsExactlyInAnyOrder("public", "archive");
            assertThat(rows).extracting(IntentFieldRoutineMethodRecord::getCandidates)
                .containsExactly(2, 2);
        });
    }

    /**
     * A qualified spelling binds the schema half, so the same ambiguous name written with its schema
     * resolves to one. The rule is the spelling view's and is not restated here; what this pins is
     * that this relation inherits it rather than matching on the name half of its own.
     */
    @Test
    void aQualifiedSpellingPicksItsSchema() {
        withRoutines(dsl ->
            assertThat(rowsAt(dsl, "Query", "qualified"))
                .extracting(r -> r.getTableSchema() + ":" + r.getCandidates())
                .containsExactly("archive:1"));
    }

    // ===== Fixture =====

    /**
     * One graph over one generated model. Every callable here also has a {@code sql_table} row,
     * which is the population the catalog walk produces: jOOQ places a table-valued function among
     * the tables, so the two are one database object read twice.
     */
    private static void withRoutines(Consumer<DSLContext> body) {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            seedSource(dsl, APP, "DIRECTORY");
            seedGraphSource(dsl, GRAPH, APP);

            callable(dsl, "public", "films_for_actor", "filmsForActor", 2);
            callable(dsl, "public", "rent_film", "rentFilm", 2);
            callable(dsl, "public", "everything", "everything", 0);
            callable(dsl, "public", "duplicated", "duplicated", 1);
            callable(dsl, "archive", "duplicated", "duplicated", 1);
            // A callable the generated model exposes no call surface for: the two names are null
            // together in the census, and no parameter row can distinguish that from taking none.
            seedTable(dsl, APP, "public", "unexposed", "FUNCTION");
            seedCatalogRoutine(dsl, APP, "public", "unexposed");
            // A stored table, which is the second cause of absence.
            seedTable(dsl, APP, "public", "film");

            seedType(dsl, GRAPH, "Query", "OBJECT");
            application(dsl, "films", "films_for_actor");
            application(dsl, "everything", "everything");
            application(dsl, "missing", "no_such_routine");
            application(dsl, "notCallable", "film");
            application(dsl, "unexposed", "unexposed");
            application(dsl, "ambiguous", "duplicated");
            application(dsl, "qualified", "archive.duplicated");

            seedField(dsl, GRAPH, "Query", "chained");
            seedRoutine(dsl, GRAPH, "Query", "chained", 0, "films_for_actor", 2);
            seedRoutine(dsl, GRAPH, "Query", "chained", 1, "rent_film", 3);

            body.accept(dsl);
        });
    }

    /** A table-valued function: its result table, its callable, and the call surface's parameters. */
    private static void callable(DSLContext dsl, String schema, String routineName,
                                 String methodName, int parameters) {
        seedTable(dsl, APP, schema, routineName, "FUNCTION");
        seedCatalogRoutine(dsl, APP, schema, routineName, ROUTINES, methodName);
        for (int position = 0; position < parameters; position++) {
            seedRoutineParameter(dsl, APP, schema, routineName, position, "p" + position);
        }
    }

    /** A {@code Query} field carrying one {@code @routine}, at the first application ordinal. */
    private static void application(DSLContext dsl, String fieldName, String routineRef) {
        seedField(dsl, GRAPH, "Query", fieldName);
        seedRoutine(dsl, GRAPH, "Query", fieldName, routineRef);
    }

    private static List<IntentFieldRoutineMethodRecord> rowsAt(DSLContext dsl, String typeName,
                                                               String fieldName) {
        return dsl.selectFrom(INTENT_FIELD_ROUTINE_METHOD)
            .where(INTENT_FIELD_ROUTINE_METHOD.GRAPH_NAME.eq(GRAPH)
                .and(INTENT_FIELD_ROUTINE_METHOD.TYPE_NAME.eq(typeName))
                .and(INTENT_FIELD_ROUTINE_METHOD.FIELD_NAME.eq(fieldName)))
            .orderBy(INTENT_FIELD_ROUTINE_METHOD.ORDINAL)
            .fetch();
    }

    /** What the spelling itself resolved to, which is what separates the two causes of absence. */
    private static List<String> spellingsOf(DSLContext dsl, String spelling) {
        return dsl.select(INTENT_SPELLED_TABLE.TABLE_NAME)
            .from(INTENT_SPELLED_TABLE)
            .where(INTENT_SPELLED_TABLE.GRAPH_NAME.eq(GRAPH)
                .and(INTENT_SPELLED_TABLE.SPELLING.eq(spelling)))
            .fetch(0, String.class);
    }
}
