package no.sikt.graphitron.rewrite.compile;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R410 slice 5 — the correctness-invariant acceptance harness, run over a realistically classified
 * schema (catalog table types, a table reference, a Relay connection, a DML insert that projects its
 * {@code @table} return). Three obligations:
 *
 * <ul>
 *   <li>the {@link TypeSpecReferenceWalk} completeness oracle: the model graph is a superset of the
 *       references the emit artifact actually contains (falsifies graph incompleteness);</li>
 *   <li>clause (a): after a schema edit driven through the incremental engine, the
 *       {@code target/graphitron-classes} tree is byte-for-byte identical to a clean full compile of
 *       the edited sources;</li>
 *   <li>clause (b): the recompile set prunes a body-only edit's reverse-dependents and includes an ABI
 *       edit's transitive dependents.</li>
 * </ul>
 *
 * <p>The harness lives here rather than in graphitron-maven-plugin because real generation over the
 * jOOQ catalog is a graphitron-module capability; the maven-plugin dev-mojo tests run without a real
 * catalog ({@code skipInitial=true}). Slice 6 wires the same pieces into {@code DevMojo}.
 */
@PipelineTier
class IncrementalCompileHarnessTest {

    private static final String OUTPUT_PACKAGE = TestConfiguration.DEFAULT_OUTPUT_PACKAGE;

    /**
     * Base schema: exercises root table read, child table reference, connection, DML insert projection,
     * plus a node type with a {@code node(id:)} lookup so the oracle also covers the NodeType /
     * QueryNodeField arms and the precise NodeIdEncoder edges.
     *
     * <p>R455 corpus extension: {@code Language.films} is an inline list reference filtered by a
     * {@code @nodeId}-decoding input. Its inline emit composes {@code Film.$fields(...)} (a same-package
     * nested-{@code $L} projection the oracle was blind to before this item), lifts a decode helper onto
     * the Language type class (exercising the {@code typeClass -> NodeIdEncoder} edge and the
     * decode-helper lift), and calls a generated {@code FilmConditions} method (exercising the inline
     * {@code typeClass -> conditions} edge). Nesting-hosted attribution is covered at unit tier in
     * {@code CompileDependencyGraphBuilderTest}.
     *
     * <p>R459 corpus extension: {@code Film.meta: FilmMeta} is a fetcher-owning plain-object nesting
     * type ({@code FilmMeta { language: Language @reference }}); FilmMeta shares Film's table context,
     * emits a {@code FilmMetaFetchers} class its {@code FilmMetaType} schema-shape wires, and its one
     * nested field is an inline single-valued {@code TableField} whose reified read references no
     * generated unit, so the fixture exercises exactly the schema-shape-to-fetcher wiring edge (the
     * node the {@code addNestedFetcherNodes} walk registers) and not the deferred nested-fetcher
     * per-field outgoing gap (filed separately as a Backlog stub).
     */
    private static final String SCHEMA = """
        interface Node { id: ID! }

        type Query {
          film: Film
          films: [Film!]! @asConnection
          node(id: ID!): Node
        }

        type Film implements Node @table(name: "film") @node {
          id: ID! @nodeId
          title: String
          language: Language @reference(path: [{key: "film_language_id_fkey"}])
          meta: FilmMeta
        }

        type FilmMeta {
          language: Language @reference(path: [{key: "film_language_id_fkey"}])
        }

        type Language @table(name: "language") {
          name: String
          films(filter: FilmFilter): [Film!] @reference(path: [{key: "film_language_id_fkey"}])
        }

        input FilmFilter @table(name: "film") {
          ids: [ID!] @nodeId(typeName: "Film")
        }

        input FilmInput @table(name: "film") { title: String }

        type Mutation {
          createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
        }
        """;

    /** The edit for clause (a): a new column-backed field on Film ({@code description} is a real film column). */
    private static final String SCHEMA_EDITED = """
        interface Node { id: ID! }

        type Query {
          film: Film
          films: [Film!]! @asConnection
          node(id: ID!): Node
        }

        type Film implements Node @table(name: "film") @node {
          id: ID! @nodeId
          title: String
          description: String
          language: Language @reference(path: [{key: "film_language_id_fkey"}])
          meta: FilmMeta
        }

        type FilmMeta {
          language: Language @reference(path: [{key: "film_language_id_fkey"}])
        }

        type Language @table(name: "language") {
          name: String
          films(filter: FilmFilter): [Film!] @reference(path: [{key: "film_language_id_fkey"}])
        }

        input FilmFilter @table(name: "film") {
          ids: [ID!] @nodeId(typeName: "Film")
        }

        input FilmInput @table(name: "film") { title: String }

        type Mutation {
          createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
        }
        """;

    @Test
    void completenessOracle_modelGraphIsSupersetOfTheReferenceWalk(@TempDir Path workDir) throws Exception {
        Generated generated = generate(workDir, SCHEMA);
        var graph = CompileDependencyGraphBuilder.fromModel(generated.model, OUTPUT_PACKAGE);
        Map<String, Set<String>> walk = TypeSpecReferenceWalk.edges(generated.emittedUnits);
        Set<String> frozenSources = frozenScaffoldFqcns();

        // Every reference the walk finds between two generated units must be present in the model-sourced
        // graph, or the graph is incomplete (a dependency it would silently skip on an incremental
        // recompile). Frozen-scaffold units are exempt AS SOURCES: their content is schema-independent,
        // so they never enter a schema-driven delta and their outgoing edges never drive a recompile;
        // modeling their template-internal references would mean reverse-engineering the fixed runtime
        // templates, which the frozen-scaffold design deliberately avoids. They stay checked as targets.
        var gaps = new LinkedHashMap<String, Set<String>>();
        walk.forEach((unit, referenced) -> {
            if (frozenSources.contains(unit)) {
                return;
            }
            var missing = new java.util.LinkedHashSet<>(referenced);
            missing.removeAll(graph.directReferences(unit));
            if (!missing.isEmpty()) {
                gaps.put(unit, missing);
            }
        });
        assertThat(gaps).as("model graph gaps vs reference walk").isEmpty();
    }

    @Test
    void clauseA_incrementalTreeEqualsCleanFullCompile(@TempDir Path incWork, @TempDir Path fullWork,
                                                       @TempDir Path incClasses, @TempDir Path fullClasses)
            throws Exception {
        List<Path> classpath = runtimeClasspath();

        // Baseline: generate S0 and compile the whole tree into the incremental output dir.
        Generated g0 = generate(incWork, SCHEMA);
        Map<String, String> hashes0 = abiHashes(g0.emittedUnits);
        try (var incEngine = new IncrementalCompileEngine(incClasses, classpath)) {
            var baseline = incEngine.compile(sources(g0.emittedUnits, g0.emittedUnits.keySet()));
            assertThat(baseline.success())
                .as("baseline compile of generated sources: %s", baseline.diagnostics())
                .isTrue();

            // Edit S0 -> S1 in the same source dir: the writer reports the delta.
            Generated g1 = generate(incWork, SCHEMA_EDITED);
            Map<String, String> hashes1 = abiHashes(g1.emittedUnits);
            var graph1 = CompileDependencyGraphBuilder.fromModel(g1.model, OUTPUT_PACKAGE);
            Set<String> abiChanged = RecompileSet.abiChanged(g1.changedUnits, hashes0, hashes1);
            Set<String> recompile = RecompileSet.compute(graph1, g1.changedUnits, abiChanged);

            assertThat(g1.changedUnits).as("an edit must produce a non-empty delta").isNotEmpty();
            var round = incEngine.compile(sources(g1.emittedUnits, recompile));
            assertThat(round.success()).as("incremental recompile: %s", round.diagnostics()).isTrue();
            incEngine.sweepOrphans(g1.emittedUnits.keySet());
        }

        // Reference: generate S1 fresh and compile the whole tree into the full output dir.
        Generated g1full = generate(fullWork, SCHEMA_EDITED);
        try (var fullEngine = new IncrementalCompileEngine(fullClasses, classpath)) {
            var full = fullEngine.compile(sources(g1full.emittedUnits, g1full.emittedUnits.keySet()));
            assertThat(full.success()).as("full compile: %s", full.diagnostics()).isTrue();
        }

        // The incremental tree must be byte-for-byte identical to the clean full compile.
        Map<String, byte[]> inc = classFileBytes(incClasses);
        Map<String, byte[]> full = classFileBytes(fullClasses);
        var onlyInc = new java.util.TreeSet<>(inc.keySet());
        onlyInc.removeAll(full.keySet());
        var onlyFull = new java.util.TreeSet<>(full.keySet());
        onlyFull.removeAll(inc.keySet());
        var differingBytes = new java.util.TreeSet<String>();
        for (String key : inc.keySet()) {
            if (full.containsKey(key) && !java.util.Arrays.equals(inc.get(key), full.get(key))) {
                differingBytes.add(key);
            }
        }
        assertThat(onlyInc).as("classes only in incremental tree").isEmpty();
        assertThat(onlyFull).as("classes only in full tree").isEmpty();
        assertThat(differingBytes).as("classes with differing bytes").isEmpty();
    }

    @Test
    void clauseB_bodyEditPrunesButAbiEditPropagates(@TempDir Path workDir) throws Exception {
        Generated g = generate(workDir, SCHEMA);
        var graph = CompileDependencyGraphBuilder.fromModel(g.model, OUTPUT_PACKAGE);
        var units = new GeneratedUnits(OUTPUT_PACKAGE);
        String filmType = units.typeClass("Film");            // referenced by the root + DML fetchers
        String queryFetchers = units.fetchers("Query");
        String mutationFetchers = units.fetchers("Mutation");

        // Sanity: the graph really does route both fetchers at the Film projection.
        assertThat(graph.directDependents(filmType)).contains(queryFetchers, mutationFetchers);

        // Body-only edit to the Film projection: nothing propagates.
        assertThat(RecompileSet.compute(graph, Set.of(filmType), Set.of()))
            .containsExactly(filmType);

        // ABI edit to the Film projection: its transitive dependents are pulled in.
        assertThat(RecompileSet.compute(graph, Set.of(filmType), Set.of(filmType)))
            .contains(filmType, queryFetchers, mutationFetchers,
                units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronSchema"));
    }

    // ------------------------------------------------------------------------------------------------

    /** The fixed, schema-independent runtime scaffolds; exempt as oracle sources (see the oracle test). */
    private static Set<String> frozenScaffoldFqcns() {
        var units = new GeneratedUnits(OUTPUT_PACKAGE);
        var frozen = new java.util.LinkedHashSet<String>();
        for (var singleton : UtilSingleton.ALL) {
            if (singleton instanceof UtilSingleton.FrozenScaffold fs) {
                frozen.add(units.singleton(fs.subPackage(), fs.simpleName()));
            }
        }
        return frozen;
    }

    private record Generated(GraphitronSchema model, Map<String, TypeSpec> emittedUnits,
                             Set<String> changedUnits) {}

    private static Generated generate(Path workDir, String schemaText) throws Exception {
        Files.createDirectories(workDir);
        Path schemaFile = workDir.resolve("schema.graphqls");
        Files.writeString(schemaFile, schemaText);

        RewriteContext ctx = new RewriteContext(
            List.of(SchemaInput.plain(schemaFile.toString())),
            workDir,
            workDir.resolve("generated-sources"),
            OUTPUT_PACKAGE,
            TestConfiguration.DEFAULT_JOOQ_PACKAGE,
            Map.of());

        var result = new GraphQLRewriteGenerator(ctx).generate();

        // The model, from the same SDL the generator classified (plain untagged schema, so a direct
        // parse + buildBundle matches the generator's attributed pipeline).
        TypeDefinitionRegistry registry = new SchemaParser().parse(directivesPrelude() + schemaText);
        var model = GraphitronSchemaBuilder.buildBundle(registry, ctx).model();
        return new Generated(model, result.emittedUnits(), result.changedUnits());
    }

    /** Renders the selected emitted units to in-memory source compilation units. */
    private static List<JavaFileObject> sources(Map<String, TypeSpec> emittedUnits, Set<String> select) {
        List<JavaFileObject> out = new ArrayList<>();
        for (var entry : emittedUnits.entrySet()) {
            if (!select.contains(entry.getKey())) {
                continue;
            }
            String fqcn = entry.getKey();
            int lastDot = fqcn.lastIndexOf('.');
            String packageName = lastDot < 0 ? "" : fqcn.substring(0, lastDot);
            out.add(JavaFile.builder(packageName, entry.getValue()).indent("    ").build().toJavaFileObject());
        }
        return out;
    }

    private static Map<String, String> abiHashes(Map<String, TypeSpec> emittedUnits) {
        var hashes = new LinkedHashMap<String, String>();
        emittedUnits.forEach((fqcn, spec) -> hashes.put(fqcn, AbiSignature.hash(spec)));
        return hashes;
    }

    /** Relative path -> bytes for every {@code .class} under {@code dir}, for byte-for-byte comparison. */
    private static Map<String, byte[]> classFileBytes(Path dir) throws Exception {
        var out = new java.util.TreeMap<String, byte[]>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))::iterator) {
                out.put(dir.relativize(p).toString(), Files.readAllBytes(p));
            }
        }
        return out;
    }

    private static List<Path> runtimeClasspath() {
        List<Path> cp = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                cp.add(Path.of(entry));
            }
        }
        return cp;
    }

    private static String directivesPrelude() {
        try (InputStream is = RewriteSchemaLoader.class.getResourceAsStream("directives.graphqls")) {
            if (is == null) throw new IllegalStateException("directives.graphqls not found on classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("Could not load directives", e);
        }
    }
}
