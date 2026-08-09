package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
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
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_EXTENSION;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_INPUT;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

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
            FactCapture.capture(store.dsl(), graph(tmp), CapturedStore.registryOf(tmp, SDL), null,
                references, new NodeDeclaration(null));
            cold = census(store.dsl());
        }
        try (var warm = GraphitronModelStore.openAt(directory)) {
            assertThat(census(warm.dsl()))
                .as("relations whose warm row count differs from a cold load's")
                .isEqualTo(cold);
        }
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
            CapturedStore.registryOf(siblingDir, SIBLING_SDL), null, List.of(),
            new NodeDeclaration(null));

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
            List.of(new SchemaRecipe.Binding("schema/**", Optional.empty(), Optional.empty())),
            List.of(".graphqls"));
        FactCapture.run(directory, new FactCapture.GraphIdentity("sibling", siblingDir, recipe),
            CapturedStore.registryOf(siblingDir, SIBLING_SDL), null, List.of(),
            new NodeDeclaration(null));
        Record siblingRow = graphRow(directory, "sibling");

        capture(directory, tmp, List.of());

        assertThat(graphRow(directory, "sibling"))
            .as("the sibling's store_graph row after this graph's run")
            .isEqualTo(siblingRow);
        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThat(store.dsl().select(STORE_GRAPH_SCHEMA_INPUT.PATTERN)
                .from(STORE_GRAPH_SCHEMA_INPUT)
                .where(STORE_GRAPH_SCHEMA_INPUT.GRAPH_NAME.eq("sibling"))
                .fetch(0, String.class))
                .as("the sibling's recipe rows").containsExactly("schema/**");
        }

        var revised = new SchemaRecipe(null,
            List.of(new SchemaRecipe.Binding("sdl/**", Optional.of("v2"), Optional.empty())),
            List.of(".graphqls"));
        FactCapture.run(directory, new FactCapture.GraphIdentity("sibling", siblingDir, revised),
            CapturedStore.registryOf(siblingDir, SIBLING_SDL), null, List.of(),
            new NodeDeclaration(null));
        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThat(store.dsl().select(STORE_GRAPH_SCHEMA_INPUT.PATTERN)
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
            List.of(new SchemaRecipe.Binding("*.graphqls", Optional.empty(), Optional.empty())),
            List.of(".graphqls"));
        FactCapture.run(directory, new FactCapture.GraphIdentity(GRAPH_NAME, tmp, recipe),
            CapturedStore.registryOf(tmp, SDL), null, List.of(), new NodeDeclaration(null));

        // The remembered recipe, rebuilt from the graph's persisted rows alone: what a freshness
        // reader with no build of the owning module has in hand. Then a pull lands a new file no
        // recorded source ever named.
        SchemaRecipe remembered;
        try (var store = GraphitronModelStore.openAt(directory)) {
            remembered = new SchemaRecipe(null,
                store.dsl().select(STORE_GRAPH_SCHEMA_INPUT.PATTERN, STORE_GRAPH_SCHEMA_INPUT.TAG,
                        STORE_GRAPH_SCHEMA_INPUT.DESCRIPTION_NOTE)
                    .from(STORE_GRAPH_SCHEMA_INPUT)
                    .where(STORE_GRAPH_SCHEMA_INPUT.GRAPH_NAME.eq(GRAPH_NAME))
                    .orderBy(STORE_GRAPH_SCHEMA_INPUT.ORDINAL)
                    .fetch(r -> new SchemaRecipe.Binding(r.value1(),
                        Optional.ofNullable(r.value2()), Optional.ofNullable(r.value3()))),
                store.dsl().select(STORE_GRAPH_SCHEMA_EXTENSION.EXTENSION)
                    .from(STORE_GRAPH_SCHEMA_EXTENSION)
                    .where(STORE_GRAPH_SCHEMA_EXTENSION.GRAPH_NAME.eq(GRAPH_NAME))
                    .orderBy(STORE_GRAPH_SCHEMA_EXTENSION.ORDINAL)
                    .fetch(0, String.class));
        }
        Files.writeString(tmp.resolve("added.graphqls"), "type Added { id: ID }");

        assertThat(remembered.expand(tmp))
            .as("the re-expansion finds the added file a check over recorded sources is blind to")
            .containsExactlyInAnyOrder(
                tmp.resolve("fixture.graphqls").toAbsolutePath().normalize(),
                tmp.resolve("added.graphqls").toAbsolutePath().normalize());
    }

    private static final String GRAPH_NAME = "WarmStartRefreshTest";

    private static FactCapture.GraphIdentity graph(Path baseDir) {
        return new FactCapture.GraphIdentity(GRAPH_NAME, baseDir);
    }

    private static void capture(Path directory, Path scratch,
                                List<CompletionData.ExternalReference> references) {
        FactCapture.run(directory, graph(scratch), CapturedStore.registryOf(scratch, SDL), null,
            references, new NodeDeclaration(null));
    }

    private static Record graphRow(Path directory, String graphName) {
        try (var store = GraphitronModelStore.openAt(directory)) {
            return store.dsl().selectFrom(STORE_GRAPH)
                .where(STORE_GRAPH.GRAPH_NAME.eq(graphName)).fetchOne();
        }
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
