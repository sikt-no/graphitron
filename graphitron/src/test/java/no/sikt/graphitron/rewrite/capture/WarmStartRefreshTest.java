package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.schema.input.SchemaRecipe;
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
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_EXTENSION;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_INPUT;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

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
     * A schema that fills registered targets, which {@link #SDL} does not: the intent relations are
     * classifications of bound coordinates, so a schema with no table binding leaves every one of
     * them empty and a case about a stale target would have nothing to make stale.
     */
    private static final String TABLE_BOUND_SDL = """
        type Query { films: [Film!]! }
        type Film @table(name: "film") { title: String, releaseYear: Int }
        """;

    @Test
    @DisplayName("a warm run ends with the rows a cold run would have produced")
    void warmAndColdAgreeRelationByRelation(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com.example.lib.LibraryClass");
        var references = referencesOver(tmp, jar);
        Path directory = tmp.resolve("graphitron-model");

        capture(directory, tmp, references);
        capture(directory, tmp, references);

        Map<String, Integer> cold;
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(tmp, SDL), CapturedStore.attributionOf(tmp), null,
                references);
            cold = census(store.dsl());
        }
        try (var warm = GraphitronModelStore.openAt(directory)) {
            assertThat(census(warm.dsl()))
                .as("relations whose warm row count differs from a cold load's")
                .isEqualTo(cold);
        }
    }

    /**
     * The recovery the first-graph refresh cadence leans on, and the one shape of stopped run this
     * family did not already cover. A capture into a store holding no graph commits its facts and
     * then refreshes the registered targets outside that transaction, one committed transaction per
     * registration, because on such a store every target is empty and a refresh inside the
     * transaction cannot be given statistics on the targets its own statements read;
     * {@code Materializations.refreshAnalysing} carries that argument. The cost is that this is the
     * one capture that can stop having left the facts complete and a target stale, and what makes
     * that acceptable is the round below.
     *
     * <p>The stopped state is constructed rather than reached, the stop being a kill inside a
     * transaction sequence with no seam to inject one at. Both halves are set: a registered target
     * emptied, and every source's stamp nulled, which is what a pass that never reached its
     * {@code commitStamps} leaves.
     *
     * <p>What this does <em>not</em> hold, so nobody reads it as more than it is: it does not fail if
     * the stamps move back ahead of the refresh, because every capture refreshes every registered
     * target for its graph unconditionally, so a stale target comes back either way. The stamp
     * placement is a consistency requirement on what a stamp claims, which {@link ClasspathSources}
     * states, rather than a defence against a reachable stale store. What this would catch is a
     * capture that stopped refreshing a target it had emptied, the shape any future narrowing of the
     * refresh would take.
     */
    @Test
    @DisplayName("a store stopped part-way through its first refresh is repaired by the next capture")
    void aStoppedFirstRefreshIsRepairedByTheNextCapture(@TempDir Path tmp) {
        var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE, testContext().codegenLoader());
        Path directory = tmp.resolve("graphitron-model");

        try (var store = GraphitronModelStore.openAt(directory)) {
            captureBound(store.dsl(), false, tmp, jooq);
            DSLContext dsl = store.dsl();
            String emptied = lastPopulatedTarget(dsl);
            assertThat(emptied)
                .as("a registered target holding rows, without which the stopped state constructed"
                    + " here is the state a finished run leaves and this case is vacuous")
                .isNotNull();
            dsl.deleteFrom(table(name(emptied))).execute();
            dsl.update(STORE_SOURCE).setNull(STORE_SOURCE.STAMP).execute();
        }

        Map<String, Integer> repaired;
        try (var store = GraphitronModelStore.openAt(directory)) {
            captureBound(store.dsl(), true, tmp, jooq);
            repaired = census(store.dsl());
        }
        Map<String, Integer> cold;
        try (var store = GraphitronModelStore.open()) {
            captureBound(store.dsl(), false, tmp, jooq);
            cold = census(store.dsl());
        }
        assertThat(repaired)
            .as("relations whose row count after a capture over the stopped store differs from a"
                + " cold load's. None: the next capture reloads what carries no stamp and refills"
                + " every registered target, so the emptied one comes back")
            .isEqualTo(cold);
    }

    @Test
    @DisplayName("an unchanged jar's classes are not written a second time")
    void anUnchangedJarIsNotReinserted(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com.example.lib.LibraryClass");
        var references = referencesOver(tmp, jar);
        Path directory = tmp.resolve("graphitron-model");

        capture(directory, tmp, references);
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
        capture(directory, tmp, references);

        assertThat(stampOf(directory, jar)).isEqualTo(first);
        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThat(store.dsl().select(JVM_CLASS.CLASS_KIND).from(JVM_CLASS)
                .where(JVM_CLASS.CLASS_NAME.eq("com.example.lib.LibraryClass"))
                .fetchOne(0, String.class))
                .as("the retained partition is the one the first run wrote, untouched")
                .isEqualTo("INTERFACE");
        }
    }

    @Test
    @DisplayName("a jar whose contents changed is re-walked")
    void aChangedJarIsRewalked(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com.example.lib.Before");
        Path directory = tmp.resolve("graphitron-model");
        capture(directory, tmp, referencesOver(tmp, jar));
        String before = stampOf(directory, jar);

        Files.delete(jar);
        jarWith(tmp, "com.example.lib.After");
        capture(directory, tmp, referencesOver(tmp, jar));

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
        capture(directory, tmp, referencesOver(tmp, jar));

        capture(directory, tmp, List.of());

        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThat(store.dsl().select(JVM_CLASS.CLASS_NAME).from(JVM_CLASS).fetch(0, String.class))
                .as("a source this run did not name is never examined and never deleted; "
                    + "it may be another graph's live dependency, and it stays until eviction")
                .containsExactly("com.example.lib.LibraryClass");
            assertThat(store.dsl().fetchCount(STORE_SOURCE, STORE_SOURCE.SOURCE_KIND.eq("JAR")))
                .as("the jar's own source row survives with its partition").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a second graph's partition survives a refresh")
    void aSiblingGraphsPartitionSurvivesARefresh(@TempDir Path tmp) throws IOException {
        Path directory = tmp.resolve("graphitron-model");
        Path siblingDir = Files.createDirectories(tmp.resolve("sibling"));
        FactCapture.run(directory, new FactCapture.GraphIdentity("sibling", siblingDir),
            FactCapture.SubjectConfig.none(), CapturedStore.registryOf(siblingDir, SIBLING_SDL),
            CapturedStore.attributionOf(siblingDir), null, List.of());

        capture(directory, tmp, List.of());
        capture(directory, tmp, List.of());

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
        FactCapture.run(directory, new FactCapture.GraphIdentity("sibling", siblingDir),
            FactCapture.SubjectConfig.of(recipe), CapturedStore.registryOf(siblingDir, SIBLING_SDL),
            CapturedStore.attributionOf(siblingDir), null, List.of());
        Record siblingRow = graphRow(directory, "sibling");

        capture(directory, tmp, List.of());

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
        FactCapture.run(directory, new FactCapture.GraphIdentity("sibling", siblingDir),
            FactCapture.SubjectConfig.of(revised), CapturedStore.registryOf(siblingDir, SIBLING_SDL),
            CapturedStore.attributionOf(siblingDir), null, List.of());
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
        capture(directory, tmp, List.of());
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
        FactCapture.run(directory, new FactCapture.GraphIdentity(GRAPH_NAME, tmp),
            FactCapture.SubjectConfig.of(recipe), CapturedStore.registryOf(tmp, SDL),
            CapturedStore.attributionOf(tmp), null, List.of());

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
     * {@link FactCapture#capture(DSLContext, boolean, FactCapture.GraphIdentity,
     * FactCapture.SubjectConfig, graphql.schema.idl.TypeDefinitionRegistry, Map, JooqCatalog, List,
     * NodeDeclaration) capture}
     * directly rather than through {@link FactCapture#run}, whose retry-then-fall-back masks a
     * deterministic failure behind a private in-memory store instead of surfacing it here.
     */
    @Test
    @DisplayName("a warm refresh over a multi-package jOOQ catalog completes")
    void aWarmRefreshOverAMultiPackageCatalogCompletes(@TempDir Path tmp) {
        var jooq = new JooqCatalog("no.sikt.graphitron.rewrite.multischemafixture",
            testContext().codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), false, graph(tmp), FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(tmp, SDL), CapturedStore.attributionOf(tmp),
                jooq, List.of());

            assertThatCode(() -> FactCapture.capture(store.dsl(), true, graph(tmp),
                FactCapture.SubjectConfig.none(), CapturedStore.registryOf(tmp, SDL),
                CapturedStore.attributionOf(tmp), jooq, List.of()))
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

    private static FactCapture.GraphIdentity graph(Path baseDir) {
        return new FactCapture.GraphIdentity(GRAPH_NAME, baseDir);
    }

    private static void capture(Path directory, Path scratch,
                                List<CompletionData.ExternalReference> references) {
        FactCapture.run(directory, graph(scratch), FactCapture.SubjectConfig.none(),
            CapturedStore.registryOf(scratch, SDL), CapturedStore.attributionOf(scratch), null,
            references);
    }

    private static Record graphRow(Path directory, String graphName) {
        try (var store = GraphitronModelStore.openAt(directory)) {
            return store.dsl().selectFrom(STORE_GRAPH)
                .where(STORE_GRAPH.GRAPH_NAME.eq(graphName)).fetchOne();
        }
    }

    /** {@link #TABLE_BOUND_SDL} captured over a real catalog, so the intent targets take rows. */
    private static void captureBound(DSLContext dsl, boolean warm, Path scratch, JooqCatalog jooq) {
        FactCapture.capture(dsl, warm, graph(scratch), FactCapture.SubjectConfig.none(),
            CapturedStore.registryOf(scratch, TABLE_BOUND_SDL),
            CapturedStore.attributionOf(scratch), jooq, List.of());
    }

    /**
     * The latest registered target in refresh order that holds a row, which is the one a stopped
     * pass would have left emptied: the pass empties and refills in that order, so the last
     * populated target is the one furthest from having been reached.
     *
     * @return the folded relation name, or null when no registered target holds a row
     */
    private static String lastPopulatedTarget(DSLContext dsl) {
        var order = Materializations.refreshOrder(dsl).registrations();
        for (int position = order.size() - 1; position >= 0; position--) {
            String target = order.get(position).targetTableName().toUpperCase();
            if (dsl.fetchCount(table(name(target))) > 0) {
                return target;
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
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    private static List<CompletionData.ExternalReference> referencesOver(Path basedir, Path... entries) {
        return CatalogBuilder.buildExternalReferences(new RewriteContext(
            List.of(), basedir, GRAPH_NAME, basedir.resolve("target/generated"),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE, List.of(entries)));
    }

    private static Path jarWith(Path directory, String className) throws IOException {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(className),
            cb -> cb.withFlags(ClassFile.ACC_PUBLIC));
        Path jar = directory.resolve("fixture-library.jar");
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(className.replace('.', '/') + ".class"));
            zip.write(bytes);
            zip.closeEntry();
        }
        return jar;
    }
}
