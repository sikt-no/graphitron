package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.run.ModelCapture;
import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.derive.DerivationStratum;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.grammar.NodeDeclaration;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.model.classpath.ClasspathCensus;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SdlVerdicts;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_EXTENSION;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_INPUT;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.capture.config.StoredRecipe;
import no.sikt.graphitron.model.run.SubjectConfig;

/**
 * What a warm store keeps and what it rewrites, under ownership scoping: a run deletes exactly
 * what it owns (its graph's partition, and the stale partitions of sources in its own input set)
 * and touches nothing else.
 *
 * <p>The claim the whole mechanism rests on is agreement, not speed: a run that starts from the
 * previous run's rows must end holding exactly what a run that started from nothing would hold,
 * for everything that run owns. The census anchor states that relation by relation, so a
 * partition that survived when it should not have, or a clear that took something a walk does not
 * put back, fails here rather than in whatever consumer eventually reads the store.
 *
 * <p>Speed is then the reason to bother, and it is stated as the one thing that is allowed to
 * differ: the classes behind an unchanged jar are not written a second time. And the shared store
 * adds the retention half: a sibling graph's partition, and a source no run named, survive
 * untouched, because a jar absent from this module's classpath may be another graph's live
 * dependency and other graphs' rows are another run's business.
 */
@PipelineTier
class WarmStartRefreshTest {

    private static final String SDL = """
        type Query { films: [Film!]! }
        type Film { title: String, year: Int }
        """;

    private static final String SIBLING_SDL = """
        type Query { actors: [Actor!]! }
        type Actor { name: String }
        """;

    /**
     * A schema that fills the stratum tables, which {@link #SDL} does not: the derived relations are
     * classifications of bound coordinates, so a schema with no table binding leaves every one of
     * them empty and a case about a stale target would have nothing to make stale.
     *
     * <p>It carries a {@code @reference} path between two bound types as well, and that half is
     * load-bearing rather than scenery. The reference stratum keys at a path element, so a schema
     * that authors no path leaves the spelling resolution, both hop arms and the walk over them
     * empty, and a case asserting that a second capture changes no row count would pass on them
     * without ever writing one. With the path here it fails on a stage that appends instead of
     * reconciling, which is the one failure mode the answer oracle cannot see. The same path is
     * written a second and third time, on an argument and on an input field the same field
     * consumes, because the argument-site and input-field walks are stages keyed at their own
     * coordinates and a field-site path puts no row in either.
     */
    private static final String TABLE_BOUND_SDL = """
        type Query {
          films(
            languageName: String
              @reference(path: [{key: "film_language_id_fkey"}]) @field(name: "name")
            filter: FilmFilter
          ): [Film!]!
        }
        input FilmFilter {
          languageName: String
            @reference(path: [{key: "film_language_id_fkey"}]) @field(name: "name")
        }
        type Film @table(name: "film") {
          title: String
          releaseYear: Int
          language: Language @reference(path: [{key: "film_language_id_fkey"}])
        }
        type Language @table(name: "language") { name: String }
        type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
        union WriteError = DbErr
        type DeleteFilmPayload { deletedId: ID, errors: [WriteError] }
        type Mutation {
          deleteFilm(filmId: Int): DeleteFilmPayload @mutation(typeName: DELETE, table: "film")
        }
        """;

    @Test
    @DisplayName("a warm run ends with the rows a cold run would have produced")
    void warmAndColdAgreeRelationByRelation(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com.example.lib.LibraryClass");
        var references = referencesOver(tmp, jar);
        Path directory = tmp.resolve("graphitron-model");

        capture(directory, tmp, jar);
        capture(directory, tmp, jar);

        Map<String, Integer> cold;
        try (var store = GraphitronModelStore.open()) {
            CapturedStore.writeSource(tmp, SDL);
            ModelCapture.capture(store.dsl(), graph(tmp), CapturedStore.corpusOf(tmp),
                entries(jar), null, now());
            cold = census(store.dsl());
        }
        try (var warm = GraphitronModelStore.openAt(directory)) {
            assertThat(census(warm.dsl()))
                .as("relations whose warm row count differs from a cold load's")
                .isEqualTo(cold);
        }
    }

    /**
     * A stage reconciles; it does not append. A capture writes a relation's graph partition by
     * clearing it and re-deriving it, and the store persists across rounds, so a second capture of
     * one graph is the ordinary case rather than an edge one: a stage that inserted without
     * clearing would fail loudly on a keyed relation and silently double a keyless one, and every
     * reader above it would fan out on the doubling.
     *
     * <p>Stated as a row-count census rather than as a comparison of answers, because the answer
     * oracle cannot see this. {@code EXCEPT} is set semantics, so both directions come back empty
     * with one side holding every row twice, and on a fresh store the stage runs once and has
     * nothing to duplicate. The census is what tells a stage that reconciles from one that appends.
     *
     * <p>{@link #TABLE_BOUND_SDL} rather than {@link #SDL}, and its {@code @reference} path is why:
     * the reference stratum's relations are keyed at a path element, so on a schema authoring none
     * this case would count zero against zero on exactly the relations it exists to hold. Its
     * mutation carrier is there for the same reason one rung up: the column-scope family's stages
     * are keyed at a mutation payload's data channel, so a schema declaring no payload leaves them
     * empty too.
     */
    @Test
    @DisplayName("a second capture of one graph leaves every relation's row count unchanged")
    void aSecondCaptureOfOneGraphDoublesNothing(@TempDir Path tmp) {
        var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE, testContext().codegenLoader());
        Path directory = tmp.resolve("graphitron-model");

        Map<String, Integer> once;
        try (var store = GraphitronModelStore.openAt(directory)) {
            captureBound(store.dsl(), tmp, jooq);
            once = census(store.dsl());
        }
        assertThat(once)
            .as("the reference stratum takes rows on this schema, without which this case counts"
                + " zero against zero on the relations it is here for")
            .containsEntry("GRAPHITRON_SPELLED_TABLE", 2)
            .hasEntrySatisfying("GRAPHITRON_FIELD_REFERENCE_STEP_HOP_KEYED",
                rows -> assertThat(rows).isPositive())
            .hasEntrySatisfying("GRAPHITRON_ARGUMENT_REFERENCE_STEP_TARGET_KEYED",
                rows -> assertThat(rows).isPositive())
            .hasEntrySatisfying("GRAPHITRON_INPUT_FIELD_REFERENCE_STEP_TARGET_KEYED",
                rows -> assertThat(rows).isPositive())
            .hasEntrySatisfying("GRAPHITRON_CARRIER_DATA_FIELD",
                rows -> assertThat(rows).isPositive());

        Map<String, Integer> twice;
        try (var store = GraphitronModelStore.openAt(directory)) {
            captureBound(store.dsl(), tmp, jooq);
            twice = census(store.dsl());
        }
        assertThat(twice)
            .as("relations whose row count moved on a second capture of the same graph; a stage"
                + " that appends instead of reconciling shows up here as a doubled count")
            .isEqualTo(once);
    }

    /**
     * The recovery the stratum's analysing cadence leans on, and the one shape of stopped run this
     * family did not already cover. A capture into a store none of whose stratum tables holds a row
     * commits its facts and then runs the derivation stratum one committed step at a time, because
     * on such a store every stratum table is empty and a step inside one transaction cannot be given
     * statistics on the tables an earlier step wrote; {@code DerivationStratum} carries that
     * argument and {@code DerivationStratum.analysingCadenceApplies} is where the condition is asked.
     * The cost is that this is the one capture that can stop having left the facts complete and a
     * stratum table stale, and what makes that acceptable is the round below.
     *
     * <p>The stopped state is constructed rather than reached, the stop being a kill inside a
     * transaction sequence with no seam to inject one at. Both halves are set: a stratum table
     * emptied, and every source's stamp nulled, which is what a pass that never reached its
     * {@code commitStamps} leaves.
     *
     * <p>What this does <em>not</em> hold, so nobody reads it as more than it is: it does not fail if
     * the stamps move back ahead of the stratum, because every capture runs every step for its graph
     * unconditionally, so a stale table comes back either way. The stamp placement is a consistency
     * requirement on what a stamp claims, which the stamp states, rather than a defence against a
     * reachable stale store. What this would catch is a capture that stopped rewriting a table it
     * had emptied, the shape any future narrowing of the stratum would take.
     */
    @Test
    @DisplayName("a store stopped part-way through its first stratum is repaired by the next capture")
    void aStoppedFirstStratumIsRepairedByTheNextCapture(@TempDir Path tmp) {
        var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE, testContext().codegenLoader());
        Path directory = tmp.resolve("graphitron-model");

        try (var store = GraphitronModelStore.openAt(directory)) {
            captureBound(store.dsl(), tmp, jooq);
            DSLContext dsl = store.dsl();
            String emptied = lastPopulatedTarget(dsl);
            assertThat(emptied)
                .as("a stratum table holding rows, without which the stopped state constructed"
                    + " here is the state a finished run leaves and this case is vacuous")
                .isNotNull();
            dsl.deleteFrom(table(name(emptied))).execute();
            dsl.update(STORE_SOURCE).setNull(STORE_SOURCE.STAMP).execute();
        }

        Map<String, Integer> repaired;
        try (var store = GraphitronModelStore.openAt(directory)) {
            captureBound(store.dsl(), tmp, jooq);
            repaired = census(store.dsl());
        }
        Map<String, Integer> cold;
        try (var store = GraphitronModelStore.open()) {
            captureBound(store.dsl(), tmp, jooq);
            cold = census(store.dsl());
        }
        assertThat(repaired)
            .as("relations whose row count after a capture over the stopped store differs from a"
                + " cold load's. None: the next capture reloads what carries no stamp and refills"
                + " every stratum table, so the emptied one comes back")
            .isEqualTo(cold);
    }

    @Test
    @DisplayName("an unchanged jar's classes are not written a second time")
    void anUnchangedJarIsNotReinserted(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com.example.lib.LibraryClass");
        var references = referencesOver(tmp, jar);
        Path directory = tmp.resolve("graphitron-model");

        capture(directory, tmp, jar);
        String first = stampOf(directory, jar);
        assertThat(first).as("a jar the scan read is a jar it stamped").isNotNull();

        // The census is identical whether the partition was retained or re-walked, so the witness
        // has to be something only a rewrite would put back. Marking the row with a value the
        // classfile does not carry is that: it survives a run that left the partition alone and
        // does not survive one that walked it again.
        try (var tampered = GraphitronModelStore.openAt(directory)) {
            tampered.dsl().update(JVM_CLASS).set(JVM_CLASS.CLASS_KIND, "INTERFACE")
                .where(JVM_CLASS.CLASS_NAME.eq("com.example.lib.LibraryClass")).execute();
        }
        capture(directory, tmp, jar);

        assertThat(stampOf(directory, jar)).isEqualTo(first);
        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThat(store.dsl().select(JVM_CLASS.CLASS_KIND).from(JVM_CLASS)
                .where(JVM_CLASS.CLASS_NAME.eq("com.example.lib.LibraryClass"))
                .fetchOne(0, String.class))
                .as("the retained partition is the one the first run wrote, untouched")
                .isEqualTo("INTERFACE");
        }
    }

    /**
     * The stamp says what the bytes were; this says when reading them began. Both come from the
     * round, and the second is the half a later reader compares against its own watch coverage to
     * decide whether it may skip re-reading at all.
     *
     * <p>Asserted as an equality against the census's own instant rather than as "not null",
     * because the failure this guards is a value that is present and late. A {@code read_at} taken
     * where the row is written would date the source after the load that read it, swallowing every
     * change that landed in between and reading as current for a partition that is not.
     */
    @Test
    @DisplayName("a source is dated with the instant its reading began, not the one it was written")
    void theRecordedCurrencyIsWhenTheRoundBeganReading(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com.example.lib.LibraryClass");
        Path directory = tmp.resolve("graphitron-model");
        var classpath =
            List.of(new ClasspathEntry(jar, ClasspathEntry.Origin.DECLARED, "com.example:library"));
        var reading = new ClasspathCensus().read(classpath, DEFAULT_JOOQ_PACKAGE);

        try (var store = GraphitronModelStore.openAt(directory)) {
            // The pass that owns the classpath, on the instant the census began reading: what it
            // records about an entry is what these assertions are about.
            ModelCapture.capture(store.dsl(), graph(tmp), SubjectConfig.none(), classpath, null,
                reading.readAt());
        }

        try (var store = GraphitronModelStore.openAt(directory)) {
            var dated = store.dsl()
                .select(STORE_SOURCE.SOURCE_NAME, STORE_SOURCE.STAMP, STORE_SOURCE.READ_AT)
                .from(STORE_SOURCE)
                .where(STORE_SOURCE.SOURCE_NAME.eq(jar.toString()))
                .fetchOne();
            assertThat(dated).as("the jar the census read has a source row").isNotNull();
            assertThat(dated.value2())
                .as("stamped, which is the half that says what the bytes were")
                .isEqualTo(reading.stamps().get(jar.toString()));
            assertThat(dated.value3())
                .as("and dated with the census's own instant, taken before it opened the entry")
                .isEqualTo(reading.readAt());
        }
    }

    /**
     * The retention decision and the stamp it is next compared against come from one reading.
     *
     * <p>The pair is the subject, not either half. A pass that re-walked an entry but kept the old
     * stamp would re-walk it forever; one that retained a partition but took the new stamp would
     * record bytes nobody read and never re-walk it again. Neither is visible in one pass, so this
     * takes three: unchanged, rewritten, unchanged again, and asserts the stamp moves exactly when
     * the rows do.
     *
     * <p>There used to be a seam to test this through, a census read before the pass whose stamps
     * were handed to it, and the failure it guarded was the pass preferring a fresh read of a jar
     * that had moved since. The classpath gatherer owns the reading now and there is no second
     * answer to prefer, so what is left to pin is that the one reading feeds both halves.
     */
    @Test
    @DisplayName("the retention decision and the recorded stamp come from one reading")
    void retentionUsesTheRoundsStamps(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com.example.lib.LibraryClass");
        Path directory = tmp.resolve("graphitron-model");
        capture(directory, tmp, jar);
        String first = stampOf(directory, jar);

        // A value the classfile does not carry, so it survives a retained partition and does not
        // survive a re-walked one.
        try (var tampered = GraphitronModelStore.openAt(directory)) {
            tampered.dsl().update(JVM_CLASS).set(JVM_CLASS.CLASS_KIND, "INTERFACE")
                .where(JVM_CLASS.CLASS_NAME.eq("com.example.lib.LibraryClass")).execute();
        }
        capture(directory, tmp, jar);
        assertThat(kindOf(directory, "com.example.lib.LibraryClass"))
            .as("the bytes had not moved, so the partition was left alone")
            .isEqualTo("INTERFACE");
        assertThat(stampOf(directory, jar))
            .as("and the stamp is the one the rows still standing were read under")
            .isEqualTo(first);

        Files.delete(jar);
        jarWith(tmp, "com.example.lib.LibraryClass", "com.example.lib.Added");
        capture(directory, tmp, jar);
        assertThat(kindOf(directory, "com.example.lib.LibraryClass"))
            .as("the bytes moved, so the partition was re-walked and the tampering is gone")
            .isEqualTo("CLASS");
        String second = stampOf(directory, jar);
        assertThat(second)
            .as("the stamp moved with the rows rather than lagging a reading behind them")
            .isNotEqualTo(first);

        capture(directory, tmp, jar);
        assertThat(stampOf(directory, jar))
            .as("and a pass over the bytes that stamp describes retains rather than re-walking")
            .isEqualTo(second);
    }

    /** One class's recorded kind, which is what a retained partition keeps and a re-walk resets. */
    private static String kindOf(Path directory, String className) {
        try (var store = GraphitronModelStore.openAt(directory)) {
            return store.dsl().select(JVM_CLASS.CLASS_KIND).from(JVM_CLASS)
                .where(JVM_CLASS.CLASS_NAME.eq(className)).fetchOne(0, String.class);
        }
    }

    @Test
    @DisplayName("a jar whose contents changed is re-walked")
    void aChangedJarIsRewalked(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com.example.lib.Before");
        Path directory = tmp.resolve("graphitron-model");
        capture(directory, tmp, jar);
        String before = stampOf(directory, jar);

        Files.delete(jar);
        jarWith(tmp, "com.example.lib.After");
        capture(directory, tmp, jar);

        try (var store = GraphitronModelStore.openAt(directory)) {
            var classes = store.dsl().select(JVM_CLASS.CLASS_NAME).from(JVM_CLASS).fetch(0, String.class);
            assertThat(classes).contains("com.example.lib.After").doesNotContain("com.example.lib.Before");
        }
        assertThat(stampOf(directory, jar)).isNotEqualTo(before);
    }

    @Test
    @DisplayName("a source that left the classpath keeps its rows for the run that still owns them")
    void anUncrawledSourceSurvivesARefresh(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com.example.lib.LibraryClass");
        Path directory = tmp.resolve("graphitron-model");
        capture(directory, tmp, jar);

        capture(directory, tmp);

        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThat(store.dsl().select(JVM_CLASS.CLASS_NAME).from(JVM_CLASS).fetch(0, String.class))
                .as("a source this run did not name is never examined and never deleted; "
                    + "it may be another graph's live dependency, and it stays until eviction")
                .containsExactly("com.example.lib.LibraryClass");
            assertThat(store.dsl().fetchCount(STORE_SOURCE, STORE_SOURCE.SOURCE_KIND.eq("JAR")))
                .as("the jar's own source row survives with its partition").isEqualTo(1);
        }
    }

    /**
     * The same claim one family further in, where it had no case and was not true.
     *
     * <p>The sibling case below asserts it over a graph-keyed relation, which the refresh scopes by
     * {@code graph_name}. The {@code sql_} family is scoped by source instead, and its lifecycle is
     * the catalog gatherer's: that walk deletes each owned source's rows across all sixteen of its
     * relations and rewrites them, leaving a source it did not read alone. Five of the sixteen were
     * nonetheless being emptied outright by the refresh beforehand, because the list it reads to
     * tell a source-partitioned relation from a rebuild-me-wholesale one is hand-written and the
     * schema grew past it. The visible shape is a half-deleted partition: a schema row standing with
     * no routine rows under it, which nothing downstream can tell from a schema that declares no
     * routine.
     *
     * <p>Seeded rather than captured from a second catalog, and that is the point of the case
     * rather than a shortcut: the claim is about a source this run never reads, so a fixture that
     * had to produce one from a real jOOQ package would be asserting something narrower. What the
     * rows have to be is well-formed and foreign, which is all this writes.
     */
    @Test
    @DisplayName("a source-partitioned catalog relation survives a run that never read its source")
    void anUnreadCatalogSourceKeepsItsWholePartition(@TempDir Path tmp) {
        Path directory = tmp.resolve("graphitron-model");
        capture(directory, tmp);

        String foreign = "com.example.othermodule.jooq";
        try (var store = GraphitronModelStore.openAt(directory)) {
            var dsl = store.dsl();
            dsl.insertInto(STORE_SOURCE, STORE_SOURCE.SOURCE_NAME, STORE_SOURCE.SOURCE_KIND,
                    STORE_SOURCE.LAST_SEEN)
                .values(foreign, "JOOQ_SCHEMA", java.time.LocalDateTime.now())
                .execute();
            dsl.insertInto(SQL_SCHEMA, SQL_SCHEMA.SOURCE_NAME, SQL_SCHEMA.TABLE_SCHEMA)
                .values(foreign, "public").execute();
            dsl.insertInto(SQL_ROUTINE, SQL_ROUTINE.SOURCE_NAME, SQL_ROUTINE.TABLE_SCHEMA,
                    SQL_ROUTINE.ROUTINE_NAME, SQL_ROUTINE.ROUTINE_TYPE)
                .values(foreign, "public", "reported_films", "FUNCTION").execute();
        }

        capture(directory, tmp);

        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThat(store.dsl().fetchCount(SQL_SCHEMA, SQL_SCHEMA.SOURCE_NAME.eq(foreign)))
                .as("the schema row, which the refresh already knew was a source's to keep")
                .isEqualTo(1);
            assertThat(store.dsl().fetchCount(SQL_ROUTINE, SQL_ROUTINE.SOURCE_NAME.eq(foreign)))
                .as("and the routine row under it, which is the same fact about the same source "
                    + "and has to survive on the same terms; a partition kept by halves is worse "
                    + "than one dropped whole, a reader having no way to tell it from a schema "
                    + "that declares no routine")
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a second graph's partition survives a refresh")
    void aSiblingGraphsPartitionSurvivesARefresh(@TempDir Path tmp) throws IOException {
        Path directory = tmp.resolve("graphitron-model");
        Path siblingDir = Files.createDirectories(tmp.resolve("sibling"));
        CapturedStore.registryOf(siblingDir, SIBLING_SDL);
        GraphitronStore.captured(directory, new GraphIdentity("sibling", siblingDir),
            CapturedStore.corpusOf(siblingDir), List.of(), null).close();

        capture(directory, tmp);
        capture(directory, tmp);

        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThat(store.dsl().select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
                .where(GRAPHQL_TYPE.GRAPH_NAME.eq("sibling")).fetch(0, String.class))
                .as("the sibling graph's SDL rows, after two of this graph's runs")
                .contains("Actor");
            assertThat(store.dsl().select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
                .where(GRAPHQL_TYPE.GRAPH_NAME.eq(GRAPH_NAME)).fetch(0, String.class))
                .as("this graph's own rows, beside them")
                .contains("Film");
        }
    }

    @Test
    @DisplayName("a graph's build identity and recipe are its own run's to rewrite")
    void recipeRowsAreRewrittenByTheirOwnRunAndUntouchedByASiblings(@TempDir Path tmp)
            throws IOException {
        Path directory = tmp.resolve("graphitron-model");
        Path siblingDir = Files.createDirectories(tmp.resolve("sibling"));
        var recipe = new SchemaRecipe(null,
            List.of(SchemaRecipe.Binding.pattern("schema/**")),
            List.of(".graphqls"));
        CapturedStore.registryOf(siblingDir, SIBLING_SDL);
        GraphitronStore.captured(directory, new GraphIdentity("sibling", siblingDir),
            SubjectConfig.of(recipe), List.of(), null).close();
        Record siblingRow = graphRow(directory, "sibling");

        capture(directory, tmp);

        assertThat(graphRow(directory, "sibling"))
            .as("the sibling's store_graph row after this graph's run")
            .isEqualTo(siblingRow);
        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThat(store.dsl().select(STORE_GRAPH_SCHEMA_INPUT.ENTRY_VALUE)
                .from(STORE_GRAPH_SCHEMA_INPUT)
                .where(STORE_GRAPH_SCHEMA_INPUT.GRAPH_NAME.eq("sibling"))
                .fetch(0, String.class))
                .as("the sibling's recipe rows").containsExactly("schema/**");
        }

        var revised = new SchemaRecipe(null,
            List.of(new SchemaRecipe.Binding(new SchemaRecipe.Entry.Pattern("sdl/**"),
                Optional.of("v2"), Optional.empty())),
            List.of(".graphqls"));
        CapturedStore.registryOf(siblingDir, SIBLING_SDL);
        GraphitronStore.captured(directory, new GraphIdentity("sibling", siblingDir),
            SubjectConfig.of(revised), List.of(), null).close();
        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThat(store.dsl().select(STORE_GRAPH_SCHEMA_INPUT.ENTRY_VALUE)
                .from(STORE_GRAPH_SCHEMA_INPUT)
                .where(STORE_GRAPH_SCHEMA_INPUT.GRAPH_NAME.eq("sibling"))
                .fetch(0, String.class))
                .as("the recipe is written fresh by the graph's own run").containsExactly("sdl/**");
        }
    }

    @Test
    @DisplayName("a schema file's recorded stamp matches a re-hash until the file is edited")
    void aSchemaFileStampMatchesUntilTheFileChanges(@TempDir Path tmp) throws IOException {
        Path directory = tmp.resolve("graphitron-model");
        capture(directory, tmp);
        Path schemaFile = tmp.resolve("fixture.graphqls");

        String recorded = stampOf(directory, schemaFile);
        assertThat(recorded).as("capture stamps a schema file that resolves to a regular file")
            .isNotNull()
            .isEqualTo(hash(schemaFile));

        Files.writeString(schemaFile, SDL + "\ntype Extra { id: ID }\n");
        assertThat(hash(schemaFile))
            .as("an edit is visible to a re-hash with no build of the owning module")
            .isNotEqualTo(recorded);
    }

    @Test
    @DisplayName("a file added under a remembered recipe's pattern is discovered by re-expansion")
    void aRecipeReExpansionDiscoversAnAddedFile(@TempDir Path tmp) throws IOException {
        Path directory = tmp.resolve("graphitron-model");
        var recipe = new SchemaRecipe(null,
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")),
            List.of(".graphqls"));
        CapturedStore.registryOf(tmp, SDL);
        GraphitronStore.captured(directory, new GraphIdentity(GRAPH_NAME, tmp),
            SubjectConfig.of(recipe), List.of(), null).close();

        // The remembered recipe, decoded from the graph's persisted rows alone: what a freshness
        // reader with no build of the owning module has in hand. Read through the production
        // decoder, which is what stops this case from drifting from the writer. Then a pull lands a
        // new file no recorded source ever named.
        SchemaRecipe remembered;
        try (var store = GraphitronModelStore.openAt(directory)) {
            remembered = StoredRecipe.decode(store.dsl(), GRAPH_NAME).orElseThrow();
        }
        Files.writeString(tmp.resolve("added.graphqls"), "type Added { id: ID }");

        var expansion = remembered.expand(tmp);
        assertThat(expansion).isInstanceOf(SchemaRecipe.Expansion.Resolved.class);
        assertThat(((SchemaRecipe.Expansion.Resolved) expansion).matches())
            .as("the re-expansion finds the added file a check over recorded sources is blind to")
            .extracting(m -> m.input().sourceName())
            .containsExactlyInAnyOrder(
                tmp.resolve("fixture.graphqls").toAbsolutePath().normalize().toString(),
                tmp.resolve("added.graphqls").toAbsolutePath().normalize().toString());
    }

    /**
     * {@code sql_referential_constraint}'s referenced-side foreign key can cross package
     * partitions (a foreign key crossing schemas the multi-schema fixture spreads over different
     * generated packages), while the catalog walk clears each package's {@code sql_} partition as
     * it visits that package. A warm refresh must not let the delete of one package's constraints
     * fire while a sibling package's stale referential rows still point at them. Calls
     * Capturing directly, so a deterministic failure surfaces here rather than behind a retry.
     */
    @Test
    @DisplayName("a warm refresh over a multi-package jOOQ catalog completes")
    void aWarmRefreshOverAMultiPackageCatalogCompletes(@TempDir Path tmp) {
        var jooq = new JooqCatalog("no.sikt.graphitron.rewrite.multischemafixture",
            testContext().codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            CapturedStore.writeSource(tmp, SDL);
            ModelCapture.capture(store.dsl(), graph(tmp), CapturedStore.corpusOf(tmp),
                List.of(), jooq, now());

            assertThatCode(() -> ModelCapture.capture(store.dsl(), graph(tmp),
                CapturedStore.corpusOf(tmp), List.of(), jooq, now()))
                .as("a warm refresh over a catalog whose foreign keys cross package partitions")
                .doesNotThrowAnyException();

            assertThat(store.dsl().fetchCount(SQL_TABLE))
                .as("the warm refresh completed and rewrote the catalog").isPositive();
            assertThat(store.dsl().fetchCount(SQL_REFERENTIAL_CONSTRAINT,
                SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SOURCE_NAME.ne(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME)))
                .as("the fixture's cross-package foreign key, still standing after the warm refresh")
                .isPositive();
        }
    }

    private static final String GRAPH_NAME = "WarmStartRefreshTest";

    private static GraphIdentity graph(Path baseDir) {
        return new GraphIdentity(GRAPH_NAME, baseDir);
    }

    /**
     * One round over {@code jars}, both passes, in the order a run has.
     *
     * <p>The entries themselves rather than a census someone else read, which is what makes the
     * assertions below about the store rather than about the fixture.
     */
    private static void capture(Path directory, Path scratch, Path... jars) {
        CapturedStore.writeSource(scratch, SDL);
        GraphitronStore.captured(directory, graph(scratch), CapturedStore.corpusOf(scratch),
            entries(jars), null).close();
    }

    /** The jars as the classified entries a build would hand over. */
    private static List<ClasspathEntry> entries(Path... jars) {
        return java.util.Arrays.stream(jars)
            .map(jar -> new ClasspathEntry(jar, ClasspathEntry.Origin.DECLARED, null))
            .toList();
    }

    /** This reading's instant, which every relation the pass fills sweeps by. */
    private static java.time.LocalDateTime now() {
        return java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private static Record graphRow(Path directory, String graphName) {
        try (var store = GraphitronModelStore.openAt(directory)) {
            return store.dsl().selectFrom(STORE_GRAPH)
                .where(STORE_GRAPH.GRAPH_NAME.eq(graphName)).fetchOne();
        }
    }

    /**
     * {@link #TABLE_BOUND_SDL} captured over a real catalog, so the intent targets take rows.
     *
     * <p>With the corpus rather than {@code SubjectConfig.none()}, which is what makes that
     * sentence true. The document gatherer re-reads the corpus from configuration, and the entry
     * relations it anchors are swept against what it wrote, so a pass given no corpus loses every
     * {@code @table} binding to that sweep and leaves the whole intent stratum empty however bound
     * the schema is. {@code CapturedStore.corpusOf} exists for exactly this and its own comment
     * names the silence.
     */
    private static void captureBound(DSLContext dsl, Path scratch, JooqCatalog jooq) {
        CapturedStore.writeSource(scratch, TABLE_BOUND_SDL);
        ModelCapture.capture(dsl, graph(scratch), CapturedStore.corpusOf(scratch), List.of(), jooq,
            now());
    }

    /**
     * The latest stratum table in step order that holds a row, which is the one a stopped pass
     * would have left emptied: the stratum empties and refills in that order, so the last populated
     * table is the one furthest from having been reached.
     *
     * @return the folded relation name, or null when no stratum table holds a row
     */
    private static String lastPopulatedTarget(DSLContext dsl) {
        var steps = DerivationStratum.steps(null);
        for (int position = steps.size() - 1; position >= 0; position--) {
            for (String written : steps.get(position).writes()) {
                String target = written.toUpperCase();
                if (dsl.fetchCount(table(name(target))) > 0) {
                    return target;
                }
            }
        }
        return null;
    }

    /** Row counts per base relation; views hold nothing of their own and are left out. */
    private static Map<String, Integer> census(DSLContext dsl) {
        var counts = new LinkedHashMap<String, Integer>();
        for (Table<?> table : Public.PUBLIC.getTables()) {
            if (table.getOptions().type() != TableOptions.TableType.VIEW) {
                counts.put(table.getName(), dsl.fetchCount(table));
            }
        }
        return counts;
    }

    private static String stampOf(Path directory, Path source) {
        try (var store = GraphitronModelStore.openAt(directory)) {
            return store.dsl().select(STORE_SOURCE.STAMP).from(STORE_SOURCE)
                .where(STORE_SOURCE.SOURCE_NAME.eq(source.toString()))
                .fetchOne(0, String.class);
        }
    }

    private static String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Recomputed here rather than delegated, the point being that a reader with only the
            // file can arrive at the recorded value; the scheme tag is part of what it arrives at.
            return "sha256:" + HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    private static List<CompletionData.ExternalReference> referencesOver(Path basedir, Path... entries) {
        return CatalogBuilder.buildExternalReferences(new RunContext(
            List.of(), basedir, GRAPH_NAME, basedir.resolve("target/generated"),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE, List.of(entries)));
    }

    private static Path jarWith(Path directory, String... classNames) throws IOException {
        Path jar = directory.resolve("fixture-library.jar");
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String className : classNames) {
                byte[] bytes = ClassFile.of().build(ClassDesc.of(className),
                    cb -> cb.withFlags(ClassFile.ACC_PUBLIC));
                zip.putNextEntry(new ZipEntry(className.replace('.', '/') + ".class"));
                zip.write(bytes);
                zip.closeEntry();
            }
        }
        return jar;
    }
}
