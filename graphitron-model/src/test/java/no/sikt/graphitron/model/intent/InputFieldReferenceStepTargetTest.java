package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_INPUT_FIELD_REFERENCE_STEP_TARGET;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedInputField;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedReferentialConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code graphitron_input_field_reference_step_target} returns: an input field's
 * {@code @reference} path walked from each table that input field is classified against.
 *
 * <p>The walk is {@code ReferenceStepTargetTest}'s, and what that class pins about the recursion
 * holds here unchanged. What is this relation's own is the departure: an input field's is its
 * consuming site's and not its own, so one authored path is walked once per resolving table, and
 * the resolving triple is in the key and in both arity partitions. The case here is the one that
 * makes it earn both.
 *
 * <p>No cross-arm case, where the two siblings each have one, and the reason is structural rather
 * than an omission. An element reaches both arms only from two departures of different kinds in one
 * arity partition, a table's foreign key beside a function result's name match. This walk's
 * partition holds its resolving table, so position zero departs from exactly one table, and no arm
 * reaches a table and a function result together from one departure, so no later position can hold
 * a departure of each kind either.
 */
class InputFieldReferenceStepTargetTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    /**
     * One authored path, classified under two arguments whose fields select from two tables, walks
     * two chains, and the second element of each is the same hop. {@code film} and {@code actor}
     * both reach {@code language}, and from there the path's second element returns to
     * {@code film} by the same two foreign keys whichever table the chain departed from. Those rows
     * agree on every column but the resolving triple, so a key without it would keep one chain's
     * rows and refuse the other's, and an arity partition without it would count four routes where
     * each chain has two.
     */
    @Test
    void onePathWalkedFromTwoResolvingTablesIsTwoChains() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "filter", "FilmFilter");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedField(dsl, GRAPH, "Query", "actors", "Actor", true);
            seedArgument(dsl, GRAPH, "Query", "actors", "filter", "FilmFilter");

            seedInputField(dsl, GRAPH, "FilmFilter", "viaLanguage", "String", 0, false, false,
                null);
            seedFieldReference(dsl, GRAPH, "FilmFilter", "viaLanguage", 0);
            seedFieldReferenceStep(dsl, GRAPH, "FilmFilter", "viaLanguage", 0, 0, "language", null);
            seedFieldReferenceStep(dsl, GRAPH, "FilmFilter", "viaLanguage", 0, 1, "film", null);
            var step = new OccurrenceStep("FilmFilter", "viaLanguage", "String");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", step);
            seedOccurrencePath(dsl, GRAPH, "Query", "actors", "filter", "FilmFilter", step);

            assertThat(rows(dsl).map(InputFieldReferenceStepTargetTest::render))
                .containsExactly(
                    "@actor 0 actor->language on actor_language_id_fkey targets=1 candidates=1",
                    "@actor 1 language->film on film_language_id_fkey targets=1 candidates=2",
                    "@actor 1 language->film on film_original_language_id_fkey targets=1"
                        + " candidates=2",
                    "@film 0 film->language on film_language_id_fkey targets=1 candidates=2",
                    "@film 0 film->language on film_original_language_id_fkey targets=1"
                        + " candidates=2",
                    "@film 1 language->film on film_language_id_fkey targets=1 candidates=2",
                    "@film 1 language->film on film_original_language_id_fkey targets=1"
                        + " candidates=2");
        });
    }

    // ===== Helpers =====

    /**
     * {@code film} and {@code actor} each declaring a foreign key to {@code language}, {@code film}
     * two of them, so the two departures reach one table by different numbers of routes and return
     * from it by the same two.
     */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String table : List.of("film", "actor", "language")) {
                seedTable(dsl, PKG, PUBLIC, table);
                seedConstraint(dsl, PKG, PUBLIC, table, table + "_pkey", "PRIMARY KEY", null);
            }
            foreignKey(dsl, "film", "film_language_id_fkey", "language");
            foreignKey(dsl, "film", "film_original_language_id_fkey", "language");
            foreignKey(dsl, "actor", "actor_language_id_fkey", "language");
            seedType(dsl, GRAPH, "String", "SCALAR");
            body.accept(dsl);
        });
    }

    /** One foreign key from {@code table} to {@code referencedTable}'s primary key. */
    private static void foreignKey(DSLContext dsl, String table, String constraintName,
                                   String referencedTable) {
        seedConstraint(dsl, PKG, PUBLIC, table, constraintName, "FOREIGN KEY", null);
        seedReferentialConstraint(dsl, PKG, PUBLIC, table, constraintName,
            PKG, PUBLIC, referencedTable, referencedTable + "_pkey");
    }

    private static Result<Record> rows(DSLContext dsl) {
        derive(dsl);
        var t = GRAPHITRON_INPUT_FIELD_REFERENCE_STEP_TARGET;
        return dsl.select(t.fields())
            .from(t)
            .where(t.GRAPH_NAME.eq(GRAPH))
            .orderBy(t.RESOLVING_TABLE, t.POSITION, t.CONSTRAINT_NAME)
            .fetch();
    }

    /** One row as the chain it belongs to, the hop it takes and the two arities beside it. */
    private static String render(Record row) {
        var t = GRAPHITRON_INPUT_FIELD_REFERENCE_STEP_TARGET;
        return "@" + row.get(t.RESOLVING_TABLE) + " " + row.get(t.POSITION) + " "
            + row.get(t.FROM_TABLE) + "->" + row.get(t.TO_TABLE)
            + " on " + row.get(t.CONSTRAINT_NAME)
            + " targets=" + row.get(t.TARGETS) + " candidates=" + row.get(t.CANDIDATES);
    }
}
