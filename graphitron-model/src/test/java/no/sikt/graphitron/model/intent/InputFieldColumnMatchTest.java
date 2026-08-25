package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_INPUT_FIELD_COLUMN_MATCH;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldBinding;
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
 * What {@code intent_input_field_column_match} resolves: the column an input field's own name
 * reaches on the table its site navigates to, which is the column the binding built from that field
 * names.
 *
 * <p>The subject splits three ways and the cases follow it. Which name is matched, which of the two
 * tiers answered, and on which table the lookup happened. The last is where this relation is not
 * simply the argument-site twin renamed: the table comes from a scope whose grain includes the
 * table the field was classified against, so one declaration can resolve to two different columns
 * on two different tables, and that is a case rather than a remark.
 *
 * <p>Every case asserts the tier that answered alongside the column. The two tiers reach the same
 * column at most sites, so a case asserting the column alone would pass with the tiers swapped, and
 * the preference between them is the classifier's rather than this relation's to invent.
 *
 * <p>What this relation deliberately does not answer gets cases too, stated as absence: a field
 * whose named type is an input object is a nesting the walk descends into rather than a name to
 * match, and a name reaching no column is the classifier's unbound carrier and not a row here.
 */
class InputFieldColumnMatchTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    private static final OccurrenceStep TITLE = new OccurrenceStep("FilmFilter", "title", "String");

    // ===== Which name is matched =====

    /** The ordinary case: the field's own name reaches a column of that name on the handed table. */
    @Test
    void anInputFieldsOwnNameReachesTheColumnItNames() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 1, "TITLE");
            inputField(dsl, "title");

            assertThat(rows(dsl).map(InputFieldColumnMatchTest::render))
                .containsExactly("FilmFilter.title@film -> film.title by JOOQ_NAME as title");
        });
    }

    /**
     * A {@code @field(name:)} binding replaces the name that is matched, which is the classifier's
     * own COALESCE. An input field is a {@code graphql_field} row like any other, so the same
     * directive relation answers here as at an output field and no second capture is involved.
     */
    @Test
    void aFieldBindingReplacesTheNameThatIsMatched() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedColumn(dsl, PKG, PUBLIC, "film", "release_year", 1, "RELEASE_YEAR");
            inputField(dsl, "year");
            seedFieldBinding(dsl, GRAPH, "FilmFilter", "year", "release_year");

            assertThat(rows(dsl).map(InputFieldColumnMatchTest::render))
                .containsExactly(
                    "FilmFilter.year@film -> film.release_year by JOOQ_NAME as release_year");
        });
    }

    /** The match is case-insensitive on both tiers, an authored spelling meeting a catalog name. */
    @Test
    void theMatchIsCaseInsensitive() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedColumn(dsl, PKG, PUBLIC, "film", "TITLE", 1, "title");
            inputField(dsl, "title");

            assertThat(rows(dsl).map(InputFieldColumnMatchTest::render))
                .containsExactly("FilmFilter.title@film -> film.TITLE by JOOQ_NAME as title");
        });
    }

    // ===== Which tier answers =====

    /**
     * The generated Java name is preferred over the SQL name, and the preference is stated against
     * an ordinal that would otherwise decide the other way: the SQL-name candidate is declared
     * first on the table, and the jOOQ-name candidate still wins. Ordering by ordinal alone would
     * pass every case where one column matches and fail this one.
     */
    @Test
    void theGeneratedNameOutranksTheSqlNameRegardlessOfOrdinal() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 1, "SOMETHING_ELSE");
            seedColumn(dsl, PKG, PUBLIC, "film", "film_title", 2, "TITLE");
            inputField(dsl, "title");

            assertThat(rows(dsl).map(InputFieldColumnMatchTest::render))
                .containsExactly("FilmFilter.title@film -> film.film_title by JOOQ_NAME as title");
        });
    }

    /** One site resolves once, whichever tier answers, so no consumer has to collapse this. */
    @Test
    void oneSiteResolvesOnce() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 1, "TITLE");
            seedColumn(dsl, PKG, PUBLIC, "film", "TiTlE", 2, "TITLE");
            inputField(dsl, "title");

            assertThat(rows(dsl)).hasSize(1);
        });
    }

    // ===== Which table the lookup happens on =====

    /**
     * A written path moves the lookup, and the column is sought on the path's terminal table rather
     * than on the one the field was classified against. The scope decided that and this relation
     * reads it, so the same name resolving on both tables must land on the terminal's column.
     */
    @Test
    void aPathedFieldIsMatchedOnItsTerminalTable() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedColumn(dsl, PKG, PUBLIC, "film", "name", 1, "NAME");
            seedColumn(dsl, PKG, PUBLIC, "actor", "name", 1, "NAME");
            seedInputField(dsl, GRAPH, "FilmFilter", "name", "String", 0, false, false, null);
            seedFieldReference(dsl, GRAPH, "FilmFilter", "name", 0);
            seedFieldReferenceStep(dsl, GRAPH, "FilmFilter", "name", 0, 0, "film_actor", null);
            seedFieldReferenceStep(dsl, GRAPH, "FilmFilter", "name", 0, 1, "actor", null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
                new OccurrenceStep("FilmFilter", "name", "String"));

            assertThat(rows(dsl).map(InputFieldColumnMatchTest::render))
                .containsExactly("FilmFilter.name@film -> actor.name by JOOQ_NAME as name");
        });
    }

    /**
     * One declaration classified against two tables resolves to two columns, and this is the case
     * the grain exists for. {@code film} and {@code actor} each carry a column the name reaches and
     * they are different columns, so a relation answering once per coordinate would be wrong at one
     * of the two sites whichever answer it kept.
     */
    @Test
    void oneDeclarationAgainstTwoTablesResolvesToTwoColumns() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedField(dsl, GRAPH, "Query", "actors", "Actor", true);
            seedArgument(dsl, GRAPH, "Query", "actors", "filter", "FilmFilter");
            seedColumn(dsl, PKG, PUBLIC, "film", "film_title", 1, "TITLE");
            seedColumn(dsl, PKG, PUBLIC, "actor", "actor_title", 1, "TITLE");
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter", TITLE);
            seedOccurrencePath(dsl, GRAPH, "Query", "actors", "filter", "FilmFilter", TITLE);

            assertThat(rows(dsl).map(InputFieldColumnMatchTest::render)).containsExactly(
                "FilmFilter.title@actor -> actor.actor_title by JOOQ_NAME as title",
                "FilmFilter.title@film -> film.film_title by JOOQ_NAME as title");
        });
    }

    // ===== What this relation declines to answer =====

    /**
     * A field whose named type is an input object is a nesting the walk descends into, and its
     * fields resolve at their own sites. A name match against the container itself would be a row
     * no consumer asked for, so the leaf-kind gate is the classifier's structure rather than an
     * addition made here. Stated with a real column of that name present, so the absence is the
     * gate's and not a missing column's.
     */
    @Test
    void anInputObjectTypedFieldIsANestingAndNotAName() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedColumn(dsl, PKG, PUBLIC, "film", "nested", 1, "NESTED");
            seedInputField(dsl, GRAPH, "FilmFilter", "nested", "Deep", 0, false, false, null);
            seedInputField(dsl, GRAPH, "Deep", "note", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
                new OccurrenceStep("FilmFilter", "nested", "Deep"));

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A name reaching no column has no row. That is the classifier's unbound carrier, and it is
     * equally the ordinary answer for a field whose content was never column-shaped, which this
     * relation does not tell apart and does not pretend to.
     */
    @Test
    void aNameReachingNoColumnHasNoRow() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 1, "TITLE");
            inputField(dsl, "rating");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** The graph partition holds: a sibling graph's coordinates resolve none of this one's sites. */
    @Test
    void aSiblingGraphResolvesNoneOfTheseSites() {
        withCatalog(dsl -> {
            filmQuery(dsl, "films", "filter");
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 1, "TITLE");
            inputField(dsl, "title");

            assertThat(rowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Fixture =====

    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String table : List.of("film", "actor", "film_actor")) {
                seedTable(dsl, PKG, PUBLIC, table);
                seedConstraint(dsl, PKG, PUBLIC, table, table + "_pkey", "PRIMARY KEY", null);
            }
            foreignKey(dsl, "film_actor", "film_actor_film_id_fkey", "film");
            foreignKey(dsl, "film_actor", "film_actor_actor_id_fkey", "actor");
            seedType(dsl, GRAPH, "String", "SCALAR");
            body.accept(dsl);
        });
    }

    private static void foreignKey(DSLContext dsl, String table, String constraintName,
                                   String referencedTable) {
        seedConstraint(dsl, PKG, PUBLIC, table, constraintName, "FOREIGN KEY", null);
        seedReferentialConstraint(dsl, PKG, PUBLIC, table, constraintName,
            PKG, PUBLIC, referencedTable, referencedTable + "_pkey");
    }

    /** A list field on {@code Query} returning a {@code film}-bound type, carrying one argument. */
    private static void filmQuery(DSLContext dsl, String fieldName, String argumentName) {
        seedTableBinding(dsl, GRAPH, "Film", "film");
        seedField(dsl, GRAPH, "Query", fieldName, "Film", true);
        seedArgument(dsl, GRAPH, "Query", fieldName, argumentName, "FilmFilter");
    }

    /** One scalar input field on {@code FilmFilter}, reached from the seeded use site. */
    private static void inputField(DSLContext dsl, String fieldName) {
        seedInputField(dsl, GRAPH, "FilmFilter", fieldName, "String", 0, false, false, null);
        seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "FilmFilter",
            new OccurrenceStep("FilmFilter", fieldName, "String"));
    }

    private static Result<Record> rows(DSLContext dsl) {
        return rowsIn(dsl, GRAPH);
    }

    private static Result<Record> rowsIn(DSLContext dsl, String graphName) {
        derive(dsl);
        return dsl.select(INTENT_INPUT_FIELD_COLUMN_MATCH.fields())
            .from(INTENT_INPUT_FIELD_COLUMN_MATCH)
            .where(INTENT_INPUT_FIELD_COLUMN_MATCH.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_INPUT_FIELD_COLUMN_MATCH.TYPE_NAME,
                INTENT_INPUT_FIELD_COLUMN_MATCH.FIELD_NAME,
                INTENT_INPUT_FIELD_COLUMN_MATCH.RESOLVING_TABLE,
                INTENT_INPUT_FIELD_COLUMN_MATCH.COLUMN_NAME)
            .fetch();
    }

    /** The site, the column it reached, the tier that answered and the name that matched. */
    private static String render(Record row) {
        return row.get(INTENT_INPUT_FIELD_COLUMN_MATCH.TYPE_NAME) + "."
            + row.get(INTENT_INPUT_FIELD_COLUMN_MATCH.FIELD_NAME) + "@"
            + row.get(INTENT_INPUT_FIELD_COLUMN_MATCH.RESOLVING_TABLE) + " -> "
            + row.get(INTENT_INPUT_FIELD_COLUMN_MATCH.TABLE_NAME) + "."
            + row.get(INTENT_INPUT_FIELD_COLUMN_MATCH.COLUMN_NAME) + " by "
            + row.get(INTENT_INPUT_FIELD_COLUMN_MATCH.MATCHED_BY) + " as "
            + row.get(INTENT_INPUT_FIELD_COLUMN_MATCH.MATCHED_NAME);
    }
}
