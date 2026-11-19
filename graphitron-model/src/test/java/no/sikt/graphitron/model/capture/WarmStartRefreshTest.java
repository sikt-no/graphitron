package no.sikt.graphitron.model.capture;

import no.sikt.graphitron.model.test.CatalogInput;

import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.run.ModelCapture;
import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.derive.DerivationStratum;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.grammar.NodeDeclaration;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SdlVerdicts;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import org.jooq.DSLContext;
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
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static no.sikt.graphitron.model.test.TestRunContext.JOOQ_PACKAGE;
import no.sikt.graphitron.model.test.TestRunContext;
import static no.sikt.graphitron.model.Tables.CODE_CLASS;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_EXTENSION;
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
 * adds the retention half: a source no run named survives untouched, because a jar absent from
 * this module's classpath may be another graph's live dependency.
 */
@org.junit.jupiter.api.Order(1)
class WarmStartRefreshTest {

    /** An instant no reading would choose, which is what makes a surviving row visible. */
    private static final LocalDateTime TAMPERED = LocalDateTime.of(1999, 12, 31, 23, 59, 58);

    private static final String SDL = """
        type Query { films: [Film!]! }
        type Film { title: String, year: Int }
        """;

    /**
     * A schema that fills the stage tables, which {@link #SDL} does not: the derived relations are
     * classifications of bound coordinates, so a schema with no table binding leaves every one of
     * them empty and a case about a stale target would have nothing to make stale.
     *
     * <p>It carries a {@code @reference} path between two bound types as well, and that half is
     * load-bearing rather than scenery. The stages resolving written references key at a path element, so a schema
     * that authors no path leaves the spelling resolution and the join path's steps empty, and a
     * case asserting that a second capture changes no row count would pass on them without ever
     * writing one. With the path here it fails on a stage that appends instead of reconciling,
     * which is the one failure mode the answer oracle cannot see. The same path is written a second
     * and third time, on an argument and on an input field the same field consumes, whose join
     * paths are walked at their own occurrences.
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
     * the relations resolving written references are keyed at a path element, so on a schema authoring none
     * this case would count zero against zero on exactly the relations it exists to hold. Its
     * mutation carrier is there for the same reason one rung up: the column-scope family's stages
     * are keyed at a mutation payload's data channel, so a schema declaring no payload leaves them
     * empty too.
     *
     * <p>The third capture runs over a stopped store, the one shape of stopped run this family did
     * not already cover. A capture commits its facts before the derivation gatherer's stages run,
     * so a capture can stop having left the facts complete and a stage table stale. The stopped
     * state is constructed rather than reached, the stop being a kill inside a transaction
     * sequence with no seam to inject one at. Both halves are set: a stage table emptied, and every
     * source's stamp nulled, which is what a pass that never reached its {@code commitStamps}
     * leaves.
     *
     * <p>What that half does <em>not</em> hold: it does not fail if the stamps move back ahead of
     * the stages, because every capture runs every step for its graph unconditionally, so a stale
     * table comes back either way. What it would catch is a capture that stopped rewriting a table
     * it had emptied, the shape any future narrowing of the stages would take.
     */
    @Test
    @DisplayName("a second capture, and one over a store stopped part-way, end with the first capture's row counts")
    void aRepeatedOrStoppedCaptureEndsWithTheColdCounts(@TempDir Path tmp) {
        var jooq = new CatalogInput(JOOQ_PACKAGE, TestRunContext.of().codegenLoader());
        Path directory = tmp.resolve("graphitron-model");

        Map<String, Integer> cold;
        try (var store = GraphitronModelStore.openAt(directory)) {
            captureBound(store.dsl(), tmp, jooq);
            cold = census(store.dsl());
        }
        assertThat(cold)
            .as("the reference relations take rows on this schema, without which this case counts"
                + " zero against zero on the relations it is here for")
            .containsEntry("GRAPHITRON_SPELLED_TABLE", 2)
            .hasEntrySatisfying("GRAPHITRON_JOIN_PATH_STEP_KEY",
                rows -> assertThat(rows).isPositive());

        try (var store = GraphitronModelStore.openAt(directory)) {
            captureBound(store.dsl(), tmp, jooq);
            assertThat(census(store.dsl()))
                .as("relations whose row count moved on a second capture of the same graph; a stage"
                    + " that appends instead of reconciling shows up here as a doubled count")
                .isEqualTo(cold);

            DSLContext dsl = store.dsl();
            String emptied = lastPopulatedTarget(dsl);
            assertThat(emptied)
                .as("a stratum table holding rows, without which the stopped state constructed"
                    + " here is the state a finished run leaves and the repair is vacuous")
                .isNotNull();
            dsl.deleteFrom(table(name(emptied))).execute();
            dsl.update(STORE_SOURCE).setNull(STORE_SOURCE.STAMP).execute();
        }

        try (var store = GraphitronModelStore.openAt(directory)) {
            captureBound(store.dsl(), tmp, jooq);
            assertThat(census(store.dsl()))
                .as("relations whose row count after a capture over the stopped store differs from"
                    + " the cold capture's. None: the next capture reloads what carries no stamp and"
                    + " refills every stratum table, so the emptied one comes back")
                .isEqualTo(cold);
        }
    }

    /**
     * A path rewritten from its object form to a list moves its element one column right, and the
     * entry keyed at the old position, at the same application and index, has to give way to the new
     * one in one capture; an error handler list rewritten the same way is the same shape. Compared
     * with a cold capture of the edited schema, which is what the warm store has to end holding.
     */
    @Test
    @DisplayName("a path edited from object to list form re-captures into a warm store")
    void anEditedPathRecapturesIntoAWarmStore(@TempDir Path tmp) {
        var jooq = new CatalogInput(JOOQ_PACKAGE, TestRunContext.of().codegenLoader());
        Path directory = tmp.resolve("graphitron-model");
        String before = """
            type Query { films: [Film!]! }
            type Film @table(name: "film") {
              title: String
              language: Language @reference(path: {key: "film_language_id_fkey"})
            }
            type Language @table(name: "language") { name: String }
            type DbErr @error(handlers: {handler: DATABASE}) { path: [String!]! message: String! }
            """;
        String after = before
            .replace("path: {key: \"film_language_id_fkey\"}",
                "path: [{key: \"film_language_id_fkey\"}, {table: \"language\"}]")
            .replace("handlers: {handler: DATABASE}", "handlers: [{handler: DATABASE}]");

        try (var store = GraphitronModelStore.openAt(directory)) {
            captureSdl(store.dsl(), tmp, jooq, before);
        }
        Map<String, Integer> warm;
        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThatCode(() -> captureSdl(store.dsl(), tmp, jooq, after))
                .as("the edited path captured into the store the old text left")
                .doesNotThrowAnyException();
            warm = census(store.dsl());
        }
        Map<String, Integer> cold;
        try (var store = GraphitronModelStore.open()) {
            captureSdl(store.dsl(), tmp, jooq, after);
            cold = census(store.dsl());
        }
        assertThat(warm)
            .as("relations whose row count after the edit differs from a cold capture of it")
            .isEqualTo(cold);
    }

    /**
     * Two code references swap lines in one edit, each landing on the position the other held, while
     * both stay alive under the same field: a relation keyed by name moves both rows in one
     * statement, and holds where a reference was written as a locator only, so neither move has to
     * wait for the other. Compared with a cold capture of the edited schema.
     */
    @Test
    @DisplayName("code references that swap lines re-capture into a warm store")
    void codeReferencesSwappingLinesRecaptureIntoAWarmStore(@TempDir Path tmp) {
        var jooq = new CatalogInput(JOOQ_PACKAGE, TestRunContext.of().codegenLoader());
        Path directory = tmp.resolve("graphitron-model");
        String aa = "  aa: String @service(service: {className: \"no.sikt.graphitron.rewrite.test.services"
            + ".SampleQueryService\", method: \"filmsByService\"}, argMapping: \"ids: aa\")\n";
        String bb = "  bb: String @service(service: {className: \"no.sikt.graphitron.rewrite.test.services"
            + ".SampleQueryService\", method: \"filmsByService\"}, argMapping: \"ids: bb\")\n";
        String before = "type Query {\n" + aa + bb + "}\n";
        String after = "type Query {\n" + bb + aa + "}\n";

        try (var store = GraphitronModelStore.openAt(directory)) {
            captureSdl(store.dsl(), tmp, jooq, before);
        }
        Map<String, Integer> warm;
        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThatCode(() -> captureSdl(store.dsl(), tmp, jooq, after))
                .as("the swapped references captured into the store the old text left")
                .doesNotThrowAnyException();
            warm = census(store.dsl());
        }
        Map<String, Integer> cold;
        try (var store = GraphitronModelStore.open()) {
            captureSdl(store.dsl(), tmp, jooq, after);
            cold = census(store.dsl());
        }
        assertThat(warm)
            .as("relations whose row count after the swap differs from a cold capture of it")
            .isEqualTo(cold);
    }

    /**
     * An error handler and a path element written in front of the ones already there move each old
     * entry one ordinal on and onto a new position, while the new entry takes the old one's ordinal:
     * entries keyed by where they were written, carrying their application and ordinal as a key of
     * their own, have to give way to each other in one capture. Compared with a cold capture of the
     * edited schema.
     */
    @Test
    @DisplayName("an entry written in front of its siblings re-captures into a warm store")
    void anEntryWrittenInFrontOfItsSiblingsRecapturesIntoAWarmStore(@TempDir Path tmp) {
        var jooq = new CatalogInput(JOOQ_PACKAGE, TestRunContext.of().codegenLoader());
        Path directory = tmp.resolve("graphitron-model");
        String before = """
            type Query { films: [Film!]! }
            type Film @table(name: "film") {
              title: String
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Language @table(name: "language") { name: String }
            type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
            """;
        String after = before
            .replace("path: [{key: \"film_language_id_fkey\"}]",
                "path: [{table: \"language\"}, {key: \"film_language_id_fkey\"}]")
            .replace("handlers: [{handler: DATABASE}]", "handlers: [{handler: GENERIC}, {handler: DATABASE}]");

        try (var store = GraphitronModelStore.openAt(directory)) {
            captureSdl(store.dsl(), tmp, jooq, before);
        }
        Map<String, Integer> warm;
        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThatCode(() -> captureSdl(store.dsl(), tmp, jooq, after))
                .as("the entries written in front captured into the store the old text left")
                .doesNotThrowAnyException();
            warm = census(store.dsl());
        }
        Map<String, Integer> cold;
        try (var store = GraphitronModelStore.open()) {
            captureSdl(store.dsl(), tmp, jooq, after);
            cold = census(store.dsl());
        }
        assertThat(warm)
            .as("relations whose row count after the edit differs from a cold capture of it")
            .isEqualTo(cold);
    }

    /**
     * A reading every gatherer vouches unchanged derives the rows the statistics were last taken
     * over, so it runs no ANALYZE; one whose configuration moved analyses again, an output package
     * being configuration a derivation reads. The catalog's entry is on the classpath, as a build's
     * is, since a catalog no entry holds is never vouched. Counted from the statements the capture
     * sends, one {@code ANALYZE TABLE} per table it changed, so the cold capture's show the count can
     * see them at all.
     */
    @Test
    @DisplayName("a reading that changed nothing skips ANALYZE, and a changed output package runs it again")
    void anUnchangedReadingSkipsAnalyze(@TempDir Path tmp) {
        var jooq = new CatalogInput(JOOQ_PACKAGE, TestRunContext.of().codegenLoader());
        CapturedStore.writeSource(tmp, SDL);
        var corpus = CapturedStore.withCatalog(CapturedStore.corpusOf(tmp), jooq);
        var analyzed = new java.util.concurrent.atomic.AtomicInteger();
        var current = new String[1];
        // The catalog's entry on the classpath, as a build's compile classpath carries it: a catalog
        // no entry holds cannot be stamped, and reads as changed on every capture.
        var classpath = List.of(no.sikt.graphitron.model.test.ClasspathCorpus.catalog());
        var counting = new org.jooq.impl.DefaultExecuteListenerProvider(new org.jooq.ExecuteListener() {
            @Override
            public void executeStart(org.jooq.ExecuteContext ctx) {
                if (String.valueOf(ctx.sql()).strip().toUpperCase().startsWith("ANALYZE")) {
                    analyzed.incrementAndGet();
                }
            }
        });
        try (var store = GraphitronModelStore.openAt(tmp.resolve("graphitron-model"))) {
            var dsl = store.dsl().configuration().deriveAppending(counting).dsl();
            java.util.function.IntSupplier capture = () -> {
                analyzed.set(0);
                ModelCapture.capture(dsl, graph(tmp), withOutput(corpus, current[0]), classpath,
                    CapturedStore.loaderOf(jooq), now());
                return analyzed.get();
            };
            current[0] = "com.example.first";
            assertThat(capture.getAsInt()).as("the cold capture analyses what it wrote").isPositive();
            assertThat(capture.getAsInt()).as("the same reading again analyses nothing").isZero();
            current[0] = "com.example.second";
            assertThat(capture.getAsInt()).as("a changed output package analyses again").isPositive();
            assertThat(capture.getAsInt()).as("and its stamp is the one compared next time").isZero();
        }
    }

    private static SubjectConfig withOutput(SubjectConfig config, String outputPackage) {
        return new SubjectConfig(config.recipe(), config.jooqPackage(), config.supergraph(),
            java.util.Optional.of(new no.sikt.graphitron.model.run.OutputCoordinates(outputPackage,
                JOOQ_PACKAGE, Path.of("target", "generated"))),
            config.tenantColumn(), config.lint(), config.sessionState(), config.maxAllowedPageSize(),
            config.outbox());
    }

    /**
     * The stamp says what the bytes were; this says when reading them began. Both come from the
     * round, and the second is the half a later reader compares against its own watch coverage to
     * decide whether it may skip re-reading at all.
     *
     * <p>Asserted as an equality against the round's own instant rather than as "not null",
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
        var readAt = java.time.LocalDateTime.now().minusMinutes(1).withNano(0);

        try (var store = GraphitronModelStore.openAt(directory)) {
            // The pass that owns the classpath, on an instant the round began before it: what it
            // records about an entry is what these assertions are about.
            ModelCapture.capture(store.dsl(), graph(tmp), SubjectConfig.none(), classpath, null,
                readAt);
        }

        try (var store = GraphitronModelStore.openAt(directory)) {
            var dated = store.dsl()
                .select(STORE_SOURCE.SOURCE_NAME, STORE_SOURCE.STAMP, STORE_SOURCE.READ_AT)
                .from(STORE_SOURCE)
                .where(STORE_SOURCE.SOURCE_NAME.eq(jar.toString()))
                .fetchOne();
            assertThat(dated).as("the jar the reading opened has a source row").isNotNull();
            assertThat(dated.value2())
                .as("stamped, which is the half that says what the bytes were")
                .isNotNull();
            assertThat(dated.value3())
                .as("and dated with the round's own instant, not the moment the row was written")
                .isEqualTo(readAt);
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
        Path jar = jarWith(tmp, "com.example.lib.LibraryClass", "com.example.lib.Dropped");
        Path directory = tmp.resolve("graphitron-model");
        capture(directory, tmp, jar);
        String first = stampOf(directory, jar);
        assertThat(first).as("a jar the scan read is a jar it stamped").isNotNull();

        // An instant no reading would choose, so it survives a retained partition and does not
        // survive a re-walked one.
        try (var tampered = GraphitronModelStore.openAt(directory)) {
            tampered.dsl().update(CODE_CLASS).set(CODE_CLASS.TOUCHED_AT, TAMPERED)
                .where(CODE_CLASS.CLASS_NAME.eq("com.example.lib.LibraryClass")).execute();
        }
        capture(directory, tmp, jar);
        assertThat(stampedAt(directory, "com.example.lib.LibraryClass"))
            .as("the bytes had not moved, so the partition was left alone")
            .isEqualTo(TAMPERED);
        assertThat(stampOf(directory, jar))
            .as("and the stamp is the one the rows still standing were read under")
            .isEqualTo(first);

        Files.delete(jar);
        jarWith(tmp, "com.example.lib.LibraryClass", "com.example.lib.Added");
        capture(directory, tmp, jar);
        assertThat(stampedAt(directory, "com.example.lib.LibraryClass"))
            .as("the bytes moved, so the partition was re-walked and the tampering is gone")
            .isNotEqualTo(TAMPERED);
        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThat(store.dsl().select(CODE_CLASS.CLASS_NAME).from(CODE_CLASS)
                    .where(CODE_CLASS.CLASS_NAME.startsWith("com.example.lib.")).fetch(0, String.class))
                .as("the re-walk wrote the new jar's classes and dropped the one it no longer holds")
                .containsExactlyInAnyOrder("com.example.lib.LibraryClass", "com.example.lib.Added");
        }
        String second = stampOf(directory, jar);
        assertThat(second)
            .as("the stamp moved with the rows rather than lagging a reading behind them")
            .isNotEqualTo(first);

        capture(directory, tmp, jar);
        assertThat(stampOf(directory, jar))
            .as("and a pass over the bytes that stamp describes retains rather than re-walking")
            .isEqualTo(second);
    }

    /** One class's stamp, which is what a retained partition keeps and a re-walk resets. */
    private static LocalDateTime stampedAt(Path directory, String className) {
        try (var store = GraphitronModelStore.openAt(directory)) {
            return store.dsl().select(CODE_CLASS.TOUCHED_AT).from(CODE_CLASS)
                .where(CODE_CLASS.CLASS_NAME.eq(className)).fetchOne(0, LocalDateTime.class);
        }
    }

    @Test
    @DisplayName("a source that left the classpath keeps its rows for the run that still owns them")
    void anUncrawledSourceSurvivesARefresh(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com.example.lib.LibraryClass");
        Path directory = tmp.resolve("graphitron-model");
        capture(directory, tmp, jar);

        capture(directory, tmp);

        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThat(store.dsl().select(CODE_CLASS.CLASS_NAME).from(CODE_CLASS)
                    .where(CODE_CLASS.SOURCE_NAME.ne("jrt:/")).fetch(0, String.class))
                .as("a source this run did not name is never examined and never deleted; "
                    + "it may be another graph's live dependency, and it stays until eviction; the "
                    + "platform's throwables, read under a source of their own, are no classpath entry's")
                .containsExactly("com.example.lib.LibraryClass");
            assertThat(store.dsl().fetchCount(STORE_SOURCE, STORE_SOURCE.SOURCE_KIND.eq("JAR")))
                .as("the jar's own source row survives with its partition").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a schema file's recorded stamp is its bytes' hash, and follows an edit on recapture")
    void aSchemaFileStampMatchesUntilTheFileChanges(@TempDir Path tmp) throws IOException {
        Path directory = tmp.resolve("graphitron-model");
        capture(directory, tmp);
        Path schemaFile = tmp.resolve("fixture.graphqls");

        String recorded = stampOf(directory, schemaFile);
        assertThat(recorded).as("capture stamps a schema file that resolves to a regular file")
            .isNotNull()
            .isEqualTo(hash(schemaFile));

        Files.writeString(schemaFile, SDL + "\ntype Extra { id: ID }\n");
        // Not capture(), which would write the fixture back over the edit.
        GraphitronStore.captured(directory, graph(tmp), CapturedStore.corpusOf(tmp), List.of(),
            null).close();
        assertThat(stampOf(directory, schemaFile))
            .as("the recapture stamps the edited bytes, which a re-hash of the file arrives at")
            .isNotEqualTo(recorded)
            .isEqualTo(hash(schemaFile));
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

    /**
     * {@link #TABLE_BOUND_SDL} captured over a real catalog, so the intent targets take rows.
     *
     * <p>With the corpus rather than {@code SubjectConfig.none()}, which is what makes that
     * sentence true. The document gatherer re-reads the corpus from configuration, and the entry
     * relations it anchors are swept against what it wrote, so a pass given no corpus loses every
     * {@code @table} binding to that sweep and leaves the whole intent_ family empty however bound
     * the schema is. {@code CapturedStore.corpusOf} exists for exactly this and its own comment
     * names the silence.
     */
    private static void captureBound(DSLContext dsl, Path scratch, CatalogInput jooq) {
        captureSdl(dsl, scratch, jooq, TABLE_BOUND_SDL);
    }

    /** {@code sdl} captured over the real catalog, as {@link #captureBound} does its fixture. */
    private static void captureSdl(DSLContext dsl, Path scratch, CatalogInput jooq, String sdl) {
        CapturedStore.writeSource(scratch, sdl);
        ModelCapture.capture(dsl, graph(scratch),
            CapturedStore.withCatalog(CapturedStore.corpusOf(scratch), jooq), List.of(),
            CapturedStore.loaderOf(jooq), now());
    }

    /**
     * The latest stage table in step order that holds a row, which is the one a stopped pass
     * would have left emptied: the stages empty and refill in that order, so the last populated
     * table is the one furthest from having been reached.
     *
     * @return the folded relation name, or null when no stage table holds a row
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

    /**
     * Row counts per base relation; views hold nothing of their own and are left out, and so are
     * {@code store_graph_write}, {@code store_graph_write_stage} and {@code store_graph_read}, which record the work each run did
     * rather than anything it read, a capture at a time: they grow by every capture, and a warm run
     * doing less is the point of a warm start. {@code graphql_ast_touched_coordinate} is left out
     * on the same terms: it holds what the latest reading rewrote, which a warm reading of
     * unchanged documents leaves empty.
     */
    private static Map<String, Integer> census(DSLContext dsl) {
        var counts = new LinkedHashMap<String, Integer>();
        for (Table<?> table : Public.PUBLIC.getTables()) {
            if (table.getOptions().type() != TableOptions.TableType.VIEW
                    && !table.equals(Public.PUBLIC.STORE_GRAPH_WRITE)
                    && !table.equals(Public.PUBLIC.STORE_GRAPH_WRITE_STAGE)
                    && !table.equals(Public.PUBLIC.STORE_GRAPH_READ)
                    && !table.equals(Public.PUBLIC.GRAPHQL_AST_TOUCHED_COORDINATE)) {
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
