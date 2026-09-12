package no.sikt.graphitron.model.capture.jooq;

import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.test.SeededStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ENUM_BINDING;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog gatherer, against the generated jOOQ classes rather than a hand-built stand-in.
 *
 * <p>A catalog gatherer tested against a catalog the test wrote is testing this file's idea of
 * jOOQ. The fixtures module generates its classes from a real Postgres, so what these read is the
 * shape the generator actually emits, and a change in that shape reaches here rather than reaching
 * a consumer.
 */
class JooqFactCaptureTest {

    private static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";
    private static final String GRAPH = "jooq-capture";
    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 1, 1, 12, 0);
    private static final LocalDateTime SECOND = FIRST.plusMinutes(1);

    @Test
    @DisplayName("the tables, columns and keys of the generated model are written")
    void theGeneratedModelIsWritten() {
        withCapture(FIRST, dsl -> {
            assertThat(dsl.select(SQL_TABLE.TABLE_NAME).from(SQL_TABLE)
                    .fetch(SQL_TABLE.TABLE_NAME))
                .as("the fixture database's tables").contains("film", "actor");

            assertThat(dsl.select(SQL_COLUMN.COLUMN_NAME).from(SQL_COLUMN)
                    .where(SQL_COLUMN.TABLE_NAME.eq("film"))
                    .orderBy(SQL_COLUMN.ORDINAL)
                    .fetch(SQL_COLUMN.COLUMN_NAME))
                .as("its columns, in the definition's own order").contains("film_id", "title");

            assertThat(dsl.fetchCount(SQL_SCHEMA)).as("at least one schema").isPositive();
            assertThat(dsl.fetchCount(SQL_PRIMARY_KEY, SQL_PRIMARY_KEY.TABLE_NAME.eq("film")))
                .as("film's primary key").isEqualTo(1);
            assertThat(dsl.select(SQL_CONSTRAINT.CONSTRAINT_TYPE).from(SQL_CONSTRAINT)
                    .fetch(SQL_CONSTRAINT.CONSTRAINT_TYPE))
                .as("constraints of every kind the walk distinguishes")
                .contains("PRIMARY KEY", "FOREIGN KEY");
        });
    }

    /**
     * Every row carries the reading that wrote it, which is what the sweep tells readings apart by.
     * Asserted over the whole family rather than one relation, because a relation whose writer
     * forgot the stamp would be swept away by the next reading and its rows would simply vanish.
     */
    @Test
    @DisplayName("every row carries the instant of the reading that wrote it")
    void everyRowCarriesItsReading() {
        withCapture(FIRST, dsl -> assertThat(dsl.fetchCount(SQL_TABLE,
                SQL_TABLE.TOUCHED_AT.isNull().or(SQL_TABLE.TOUCHED_AT.ne(FIRST))))
            .as("no table row is unstamped or stamped with another reading").isZero());
    }

    /**
     * A second reading of the same catalog leaves the same rows. The upsert handles a row that is
     * still there, and the sweep must not take it: a gatherer whose sweep predicate was wrong would
     * empty the family on the second pass rather than on the first, which is the failure a single
     * capture cannot see.
     */
    @Test
    @DisplayName("capturing twice leaves one catalog's rows, restamped")
    void capturingTwiceIsIdempotent() {
        try (var store = GraphitronStore.inMemory()) {
            SeededStore.seedGraph(store.dsl(), GRAPH);
            var jooq = new JooqCatalog(JOOQ_PACKAGE);
            JooqFactCapture.capture(store.dsl(), GRAPH, jooq, FIRST);
            int afterFirst = store.dsl().fetchCount(SQL_TABLE);
            int columnsAfterFirst = store.dsl().fetchCount(SQL_COLUMN);

            JooqFactCapture.capture(store.dsl(), GRAPH, jooq, SECOND);

            assertThat(store.dsl().fetchCount(SQL_TABLE))
                .as("the second reading replaces the first rather than adding to or emptying it")
                .isEqualTo(afterFirst).isPositive();
            assertThat(store.dsl().fetchCount(SQL_COLUMN)).isEqualTo(columnsAfterFirst);
            assertThat(store.dsl().fetchCount(SQL_TABLE, SQL_TABLE.TOUCHED_AT.eq(SECOND)))
                .as("and every row now carries the second reading").isEqualTo(afterFirst);
        }
    }

    /**
     * A binding names the database enum, not only the Java class it binds to.
     *
     * <p>These two columns held null until 2026-09-12, and nothing said so: the family had two
     * producers, one per capture entry point, and the other one filled them, so on any build that
     * ran both the rows a reader saw were complete. Comparing the two producers row by row is what
     * found it, and this is the half of that comparison worth keeping now that there is one
     * producer left. A reader resolving a column's enum needs the coordinate; the class name alone
     * does not carry it.
     */
    @Test
    @DisplayName("an enum binding carries the database enum's own coordinate, not just the class")
    void anEnumBindingCarriesItsCatalogCoordinate() {
        withCapture(FIRST, dsl -> {
            var rows = dsl.select(SQL_ENUM_BINDING.CLASS_FQN, SQL_ENUM_BINDING.TABLE_SCHEMA,
                    SQL_ENUM_BINDING.TYPE_NAME)
                .from(SQL_ENUM_BINDING).fetch();

            assertThat(rows)
                .as("the fixture catalog binds at least one column to a generated enum")
                .isNotEmpty();
            assertThat(rows)
                .as("every binding says which database enum it stands for")
                .allSatisfy(row -> {
                    assertThat(row.value3()).as("the enum's own name, for " + row.value1())
                        .isNotNull();
                    assertThat(row.value2()).as("the schema it lives in, for " + row.value1())
                        .isNotNull();
                });
        });
    }

    /**
     * The sweep's own claim: a row this reading did not touch goes. Stood up by planting a row for
     * a table the catalog does not have, which is what a dropped table leaves behind.
     */
    @Test
    @DisplayName("a table the catalog no longer has is swept")
    void aTableTheCatalogNoLongerHasIsSwept() {
        try (var store = GraphitronStore.inMemory()) {
            SeededStore.seedGraph(store.dsl(), GRAPH);
            var jooq = new JooqCatalog(JOOQ_PACKAGE);
            JooqFactCapture.capture(store.dsl(), GRAPH, jooq, FIRST);
            String source = store.dsl().select(SQL_TABLE.SOURCE_NAME).from(SQL_TABLE)
                .limit(1).fetchOne(SQL_TABLE.SOURCE_NAME);
            String schema = store.dsl().select(SQL_TABLE.TABLE_SCHEMA).from(SQL_TABLE)
                .limit(1).fetchOne(SQL_TABLE.TABLE_SCHEMA);
            store.dsl().insertInto(SQL_TABLE, SQL_TABLE.SOURCE_NAME, SQL_TABLE.TABLE_SCHEMA,
                    SQL_TABLE.TABLE_NAME, SQL_TABLE.TABLE_TYPE, SQL_TABLE.JOOQ_NAME,
                    SQL_TABLE.CLASS_FQN, SQL_TABLE.RECORD_CLASS_FQN, SQL_TABLE.TOUCHED_AT)
                .values(source, schema, "dropped_by_the_dba", "TABLE", "DROPPED_BY_THE_DBA",
                    "gone.Dropped", "gone.DroppedRecord", FIRST.minusDays(1))
                .execute();

            JooqFactCapture.capture(store.dsl(), GRAPH, jooq, SECOND);

            assertThat(store.dsl().fetchCount(SQL_TABLE,
                    SQL_TABLE.TABLE_NAME.eq("dropped_by_the_dba")))
                .as("what the catalog no longer describes is gone").isZero();
            assertThat(store.dsl().fetchCount(SQL_TABLE, SQL_TABLE.TABLE_NAME.eq("film")))
                .as("and what it still describes stands").isEqualTo(1);
        }
    }

    private static void withCapture(LocalDateTime touchedAt, java.util.function.Consumer<DSLContext> body) {
        try (var store = GraphitronStore.inMemory()) {
            SeededStore.seedGraph(store.dsl(), GRAPH);
            JooqFactCapture.capture(store.dsl(), GRAPH, new JooqCatalog(JOOQ_PACKAGE), touchedAt);
            body.accept(store.dsl());
        }
    }
}
