package no.sikt.graphitron.model.capture.jooq;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.SeededStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN_SPELLING;
import static no.sikt.graphitron.model.Tables.SQL_NAME_MATCHED_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ENUM_BINDING;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static no.sikt.graphitron.model.test.TestRunContext.JOOQ_PACKAGE;

/**
 * The catalog gatherer, against the generated jOOQ classes rather than a hand-built stand-in.
 *
 * <p>A catalog gatherer tested against a catalog the test wrote is testing this file's idea of
 * jOOQ. The fixtures module generates its classes from a real Postgres, so what these read is the
 * shape the generator actually emits, and a change in that shape reaches here rather than reaching
 * a consumer.
 */
class JooqFactCaptureTest {

    private static final String GRAPH = "jooq-capture";
    private static final String OTHER = "jooq-capture-other";
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
     * A column's declared length is what its type says, and nothing when it says none: a
     * {@code text} column and an integer one both read null, which is what
     * {@code DataType.lengthDefined()} answers where {@code hasLength()} would claim a length of 0.
     * Read once off the catalog projection and once off the captured rows, since the two must agree.
     */
    @Test
    @DisplayName("a column's declared length is captured, and is null where the type declares none")
    void aColumnsDeclaredLengthIsCaptured() {
        var jooq = new JooqCatalog(JOOQ_PACKAGE);
        java.util.function.BiFunction<String, String, Integer> facts = (table, column) ->
            jooq.columnFactsOf(jooq.findTable(table).asEntry().orElseThrow().table()).stream()
                .filter(f -> f.sqlName().equals(column)).findFirst().orElseThrow().declaredLength();
        assertThat(facts.apply("film", "title")).as("varchar(255)").isEqualTo(255);
        assertThat(facts.apply("film", "description")).as("text").isNull();
        assertThat(facts.apply("film", "film_id")).as("serial").isNull();
        assertThat(facts.apply("language", "name")).as("char(20)").isEqualTo(20);

        withCapture(FIRST, dsl -> {
            java.util.function.BiFunction<String, String, Integer> captured = (table, column) ->
                dsl.select(SQL_COLUMN.DECLARED_LENGTH).from(SQL_COLUMN)
                    .where(SQL_COLUMN.TABLE_SCHEMA.eq("public"), SQL_COLUMN.TABLE_NAME.eq(table),
                        SQL_COLUMN.COLUMN_NAME.eq(column))
                    .fetchSingle(SQL_COLUMN.DECLARED_LENGTH);
            assertThat(captured.apply("film", "title")).isEqualTo(255);
            assertThat(captured.apply("film", "description")).isNull();
            assertThat(captured.apply("film", "film_id")).isNull();
            assertThat(captured.apply("language", "name")).isEqualTo(20);
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
            capture(store.dsl(), GRAPH, loader(), List.of(ClasspathCorpus.catalog()), FIRST);
            int afterFirst = store.dsl().fetchCount(SQL_TABLE);
            int columnsAfterFirst = store.dsl().fetchCount(SQL_COLUMN);

            readAgain(store.dsl(), GRAPH, loader(), List.of(ClasspathCorpus.catalog()), SECOND);

            assertThat(store.dsl().fetchCount(SQL_TABLE))
                .as("the second reading replaces the first rather than adding to or emptying it")
                .isEqualTo(afterFirst).isPositive();
            assertThat(store.dsl().fetchCount(SQL_COLUMN)).isEqualTo(columnsAfterFirst);
            assertThat(store.dsl().fetchCount(SQL_REFERENTIAL_CONSTRAINT,
                SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SOURCE_NAME.ne(
                    SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME)))
                .as("the fixture's foreign key across package partitions, still standing after the"
                    + " second reading cleared and rewrote each package in turn")
                .isPositive();
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
            capture(store.dsl(), GRAPH, loader(), List.of(ClasspathCorpus.catalog()), FIRST);
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

            readAgain(store.dsl(), GRAPH, loader(), List.of(ClasspathCorpus.catalog()), SECOND);

            assertThat(store.dsl().fetchCount(SQL_TABLE,
                    SQL_TABLE.TABLE_NAME.eq("dropped_by_the_dba")))
                .as("what the catalog no longer describes is gone").isZero();
            assertThat(store.dsl().fetchCount(SQL_TABLE, SQL_TABLE.TABLE_NAME.eq("film")))
                .as("and what it still describes stands").isEqualTo(1);
        }
    }

    /**
     * A catalog is the bytes of the entry it is generated into, so a second graph reading the same
     * entry leaves the rows the first one wrote and only claims them.
     */
    @Test
    @DisplayName("a second graph on an unchanged catalog claims its rows without reading it")
    void aSecondGraphOnAnUnchangedCatalogClaimsWithoutReading() {
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            SeededStore.seedGraph(dsl, GRAPH);
            SeededStore.seedGraph(dsl, OTHER);
            capture(dsl, GRAPH, loader(), List.of(ClasspathCorpus.catalog()), FIRST);
            int tables = dsl.fetchCount(SQL_TABLE);

            capture(dsl, OTHER, loader(), List.of(ClasspathCorpus.catalog()), SECOND);

            assertThat(dsl.fetchCount(SQL_TABLE, SQL_TABLE.TOUCHED_AT.eq(FIRST)))
                .as("the first reading's rows stand, neither rewritten nor swept").isEqualTo(tables).isPositive();
            assertThat(claims(dsl, OTHER)).as("and the second graph claims the catalog")
                .isEqualTo(claims(dsl, GRAPH)).isNotEmpty();
        }
    }

    /**
     * The name-matched keys and the column spellings read the {@code sql_} family and nothing else,
     * and a catalog left alone moves no {@code sql_} row, so a vouched capture leaves both as the
     * last reading derived them. Counted from the statements the second capture sends, so the rows
     * standing is not merely a re-derivation that came out the same.
     */
    @Test
    @DisplayName("a catalog left alone re-derives neither its name-matched keys nor its column spellings")
    void anUnchangedCatalogReDerivesNothing() {
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            SeededStore.seedGraph(dsl, GRAPH);
            SeededStore.seedGraph(dsl, OTHER);
            capture(dsl, GRAPH, loader(), List.of(ClasspathCorpus.catalog()), FIRST);
            int spellings = dsl.fetchCount(SQL_COLUMN_SPELLING);
            int keys = dsl.fetchCount(SQL_NAME_MATCHED_KEY_COLUMN);
            var touching = new java.util.concurrent.atomic.AtomicInteger();
            var counting = dsl.configuration().deriveAppending(new org.jooq.impl.DefaultExecuteListenerProvider(
                new org.jooq.ExecuteListener() {
                    @Override
                    public void executeStart(org.jooq.ExecuteContext ctx) {
                        String sql = String.valueOf(ctx.sql()).toUpperCase();
                        if (sql.contains("SQL_COLUMN_SPELLING") || sql.contains("SQL_NAME_MATCHED_KEY_COLUMN")) {
                            touching.incrementAndGet();
                        }
                    }
                })).dsl();

            capture(counting, OTHER, loader(), List.of(ClasspathCorpus.catalog()), SECOND);

            assertThat(touching.get()).as("statements the vouched capture sent to either relation").isZero();
            assertThat(dsl.fetchCount(SQL_COLUMN_SPELLING)).as("the spellings stand").isEqualTo(spellings).isPositive();
            assertThat(dsl.fetchCount(SQL_NAME_MATCHED_KEY_COLUMN)).as("and the name-matched keys").isEqualTo(keys);
        }
    }

    @Test
    @DisplayName("a catalog whose entry is not what it was last read from is read again")
    void aCatalogWhoseEntryChangedIsReadAgain() {
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            SeededStore.seedGraph(dsl, GRAPH);
            capture(dsl, GRAPH, loader(), List.of(ClasspathCorpus.catalog()), FIRST);
            // What an entry rebuilt since leaves: the stamp the catalog was read under is not its stamp.
            dsl.update(STORE_SOURCE).set(STORE_SOURCE.STAMP, "sha256:00")
                .where(STORE_SOURCE.SOURCE_KIND.eq("JOOQ_SCHEMA")).execute();

            capture(dsl, GRAPH, loader(), List.of(ClasspathCorpus.catalog()), SECOND);

            assertThat(dsl.fetchCount(SQL_TABLE, SQL_TABLE.TOUCHED_AT.ne(SECOND)))
                .as("every row is the second reading's").isZero();
        }
    }

    @Test
    @DisplayName("a catalog whose reading committed but was never vouched for is read again")
    void anUnvouchedCatalogIsReadAgain() {
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            SeededStore.seedGraph(dsl, GRAPH);
            capture(dsl, GRAPH, loader(), List.of(ClasspathCorpus.catalog()), FIRST);
            // What a capture stopped between the rows and the stamp leaves.
            dsl.update(STORE_SOURCE).setNull(STORE_SOURCE.STAMP)
                .where(STORE_SOURCE.SOURCE_KIND.eq("JOOQ_SCHEMA")).execute();

            capture(dsl, GRAPH, loader(), List.of(ClasspathCorpus.catalog()), SECOND);

            assertThat(dsl.fetchCount(SQL_TABLE, SQL_TABLE.TOUCHED_AT.ne(SECOND)))
                .as("every row is the second reading's").isZero();
            assertThat(dsl.fetchCount(STORE_SOURCE, STORE_SOURCE.SOURCE_KIND.eq("JOOQ_SCHEMA")
                    .and(STORE_SOURCE.STAMP.isNull())))
                .as("and the catalog is vouched for now").isZero();
        }
    }

    /**
     * The stamp is the entry's, so the same classes found under another entry are another reading,
     * however alike the bytes.
     */
    @Test
    @DisplayName("a catalog found under another entry than the one it was read from is read again")
    void aCatalogUnderAnotherEntryIsReadAgain(@TempDir Path elsewhere) throws Exception {
        String resource = JOOQ_PACKAGE.replace('.', '/') + "/DefaultCatalog.class";
        Path copy = elsewhere.resolve(resource);
        Files.createDirectories(copy.getParent());
        try (var in = loader().getResource(resource).openStream()) {
            Files.copy(in, copy);
        }
        var moved = new ClassLoader(loader()) {
            @Override
            public URL getResource(String name) {
                try {
                    return name.equals(resource) ? copy.toUri().toURL() : super.getResource(name);
                } catch (java.net.MalformedURLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        var other = new ClasspathEntry(elsewhere, ClasspathEntry.Origin.DECLARED, "elsewhere", null);
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            SeededStore.seedGraph(dsl, GRAPH);
            SeededStore.seedGraph(dsl, OTHER);
            capture(dsl, GRAPH, loader(), List.of(ClasspathCorpus.catalog()), FIRST);

            int tables = dsl.fetchCount(SQL_TABLE);

            capture(dsl, OTHER, moved, List.of(ClasspathCorpus.catalog(), other), SECOND);

            assertThat(dsl.fetchCount(SQL_TABLE, SQL_TABLE.TOUCHED_AT.eq(SECOND)))
                .as("the second reading wrote the catalog again, under the other entry's sources")
                .isEqualTo(tables).isPositive();
            assertThat(dsl.fetchCount(SQL_TABLE, SQL_TABLE.TOUCHED_AT.eq(FIRST)))
                .as("and the first entry's rows stand, another source no sweep of it reaches")
                .isEqualTo(tables);
            assertThat(claims(dsl, OTHER)).as("each graph claims its own entry's sources")
                .doesNotContainAnyElementsOf(claims(dsl, GRAPH));
        }
    }

    /**
     * A capture that reads the catalog again rather than finding it vouched for: what a rebuilt
     * entry leaves, its stamp no longer the one the catalog was read under.
     */
    private static void readAgain(DSLContext dsl, String graph, ClassLoader loader,
                                  List<ClasspathEntry> classpath, LocalDateTime touchedAt) {
        dsl.update(STORE_SOURCE).setNull(STORE_SOURCE.STAMP)
            .where(STORE_SOURCE.SOURCE_KIND.eq("JOOQ_SCHEMA")).execute();
        capture(dsl, graph, loader, classpath, touchedAt);
    }

    private static void capture(DSLContext dsl, String graph, ClassLoader loader,
                                List<ClasspathEntry> classpath, LocalDateTime touchedAt) {
        JooqFactCapture.capture(dsl, graph, JOOQ_PACKAGE, loader, classpath, null, touchedAt);
    }

    private static List<String> claims(DSLContext dsl, String graph) {
        return dsl.select(STORE_GRAPH_SOURCE.SOURCE_NAME).from(STORE_GRAPH_SOURCE)
            .join(STORE_SOURCE).on(STORE_SOURCE.SOURCE_NAME.eq(STORE_GRAPH_SOURCE.SOURCE_NAME))
            .where(STORE_GRAPH_SOURCE.GRAPH_NAME.eq(graph), STORE_SOURCE.SOURCE_KIND.eq("JOOQ_SCHEMA"))
            .orderBy(STORE_GRAPH_SOURCE.SOURCE_NAME)
            .fetch(STORE_GRAPH_SOURCE.SOURCE_NAME);
    }

    private static void withCapture(LocalDateTime touchedAt, java.util.function.Consumer<DSLContext> body) {
        try (var store = GraphitronStore.inMemory()) {
            SeededStore.seedGraph(store.dsl(), GRAPH);
            capture(store.dsl(), GRAPH, loader(), List.of(ClasspathCorpus.catalog()), touchedAt);
            body.accept(store.dsl());
        }
    }

    /** The test's own classpath, which holds the generated catalog. */
    private static ClassLoader loader() {
        return Thread.currentThread().getContextClassLoader();
    }
}
