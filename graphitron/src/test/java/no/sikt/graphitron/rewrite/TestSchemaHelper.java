package no.sikt.graphitron.rewrite;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.schema.SchemaLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import no.sikt.graphitron.model.classpath.ClasspathScanner;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.grammar.NodeDeclaration;
import no.sikt.graphitron.model.derive.ResolvedKeyProjections;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaInputAttribution;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.schema.AttributedRegistry;

/**
 * Shared test helper that builds a {@link GraphitronSchema} from inline SDL,
 * loading Graphitron directive definitions from rewrite's own classpath resource.
 */
public final class TestSchemaHelper {

    private static final String DIRECTIVES = loadDirectives();

    /**
     * Relay {@code Node} interface, injected into every test SDL as a harmless orphan unless the
     * test declares its own implementation. {@code @node} typeclassification requires the type
     * to {@code implements Node}; bundling the declaration here keeps test SDL focused on the
     * actual subject.
     */
    private static final String NODE_INTERFACE = "interface Node { id: ID! }\n";

    private TestSchemaHelper() {}

    /**
     * The attribution map for a fixture that handed the loader exactly {@code files}, as capture's
     * stamp lookup needs it: a source name the map does not resolve is a gap between the run's
     * inputs and what the parser handed back, and capture refuses to guess at one. Every file is a
     * {@code file} arm, because a source that reached a real parse necessarily is one.
     */
    public static java.util.Map<String, no.sikt.graphitron.model.schema.input.SchemaInput>
            attribution(java.nio.file.Path... files) {
        return no.sikt.graphitron.model.schema.input.SchemaInputAttribution.build(
            java.util.Arrays.stream(files)
                .map(no.sikt.graphitron.model.schema.input.SchemaInput::file)
                .toList());
    }

    public static GraphitronSchema buildSchema(String schemaText) {
        return buildSchema(schemaText, TestConfiguration.testContext());
    }

    public static GraphitronSchema buildSchema(String schemaText, RunContext ctx) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(prelude(schemaText) + schemaText);
        return GraphitronSchemaBuilder.build(registry, ctx);
    }

    /**
     * The build-time diagnostics rendered one per line as {@code coordinate: message}, for assertions
     * about a cause that is reported at the coordinate carrying it rather than on the consuming
     * field. Input-field failures are minted there, one located diagnostic per failure, while the
     * consuming field keeps a single rejection stating the consequence, so a test that wants the
     * cause text reads this and a test that wants the consequence reads the field's own rejection.
     *
     * <p>The coordinate is rendered from the {@link ValidationError} rather than read out of the
     * message because the typed sub-seal arms ({@code ReflectionError} and siblings) treat
     * {@code prefixedWith} as a no-op by design, so their message carries no coordinate prose.
     */
    public static String diagnosticMessages(GraphitronSchema schema) {
        return schema.diagnostics().stream()
            .map(d -> d.coordinate() + ": " + d.message())
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    public static GraphitronSchemaBuilder.Bundle buildBundle(String schemaText) {
        return buildBundle(schemaText, TestConfiguration.testContext());
    }

    /**
     * The bundle a run produces for {@code schemaText}, with the emitted schema taken off a store
     * rather than off the walk that built the model beside it.
     *
     * <p>A run reads its emitted schema from the facts, so a fixture that read the walk's
     * synthesis was testing a producer the generator no longer uses. Every caller here goes
     * through this arm, which is why the migration is one method rather than the hundred-odd call
     * sites above it.
     *
     * <p>The capture is the cost, and it is per call. What a fixture gets back is what a run would
     * emit for the same document, which is the only version of this worth asserting against.
     */
    public static GraphitronSchemaBuilder.Bundle buildBundle(String schemaText, RunContext ctx) {
        var bundle = GraphitronSchemaBuilder.buildBundle(parseRegistryWithPrelude(schemaText), ctx);
        return new GraphitronSchemaBuilder.Bundle(bundle.model(), emittedSchema(schemaText, ctx),
            bundle.federationLink(), bundle.usesOneOf(), bundle.decodeLedger());
    }

    /**
     * The emitted schemas already derived in this JVM, keyed by the exact text captured.
     *
     * <p>The derivation is a function of that text and nothing else: the capture is handed the
     * document and no context, so two fixtures naming one schema get one answer. Without this a
     * module that builds a bundle per case boots a store per case, which the thread-confined
     * store's budget refuses and is right to: the boots it counts are the ones nobody meant to pay
     * for twice.
     */
    private static final java.util.Map<String, graphql.schema.GraphQLSchema> EMITTED =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** The emitted schema for {@code schemaText}, derived from a store captured over it. */
    private static graphql.schema.GraphQLSchema emittedSchema(String schemaText, RunContext ctx) {
        String captured = (schemaText.contains("interface Node") ? "" : NODE_INTERFACE) + schemaText;
        return EMITTED.computeIfAbsent(captured, TestSchemaHelper::deriveEmittedSchema);
    }

    private static graphql.schema.GraphQLSchema deriveEmittedSchema(String captured) {
        // Its own store, never the thread's: a fixture that holds a CapturedStore across its
        // cases would have the rows it captured cleared out from under it by this one.
        try (var store = no.sikt.graphitron.model.test.CapturedStore.ownStore(
                java.nio.file.Files.createTempDirectory("emitted"),
                captured)) {
            var assembly = no.sikt.graphitron.model.schema.SchemaAssembly.of(
                no.sikt.graphitron.model.schema.EmittedRegistry.of(store.registry(),
                    new no.sikt.graphitron.model.read.StoreHandle(store.dsl(),
                        no.sikt.graphitron.model.test.CapturedStore.GRAPH)));
            if (assembly instanceof no.sikt.graphitron.model.schema.SchemaAssembly.Assembled a) {
                return a.schema();
            }
            throw new IllegalStateException("the emitted registry did not assemble: " + assembly);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * The plan a run produces for {@code schemaText}, store-backed: capture fills a store against
     * the test catalog, the plan's producers read it, and the store closes behind them, the
     * relations it yielded being values.
     *
     * <p>The counterpart of {@link #buildSchema} for the plan tier, and the arm to reach for as
     * producers convert: a test whose subject is a relation read from the store gets nothing from
     * {@code EmitPlan.produceWithoutStore}, which plans that relation empty by construction.
     *
     * @param directory a temp directory the fixture is written into, typically a {@code @TempDir}
     */
    public static no.sikt.graphitron.plan.EmitPlan storeBackedPlan(java.nio.file.Path directory,
            String schemaText) {
        return storeBackedPlan(directory, schemaText, TestConfiguration.testContext());
    }

    /** {@link #storeBackedPlan(java.nio.file.Path, String)} under a context the caller names. */
    public static no.sikt.graphitron.plan.EmitPlan storeBackedPlan(java.nio.file.Path directory,
            String schemaText, RunContext ctx) {
        return storeBackedPlan(directory, schemaText, ctx,
            no.sikt.graphitron.model.derive.ResolvedKeyProjections.Projections.empty());
    }

    /**
     * The same with key projections the caller states rather than the empty set. Those are the one
     * plan input still resolved outside this window in production, so a test whose subject is a
     * projected {@code argMapping} spells them here as the store would have resolved them.
     */
    public static no.sikt.graphitron.plan.EmitPlan storeBackedPlan(java.nio.file.Path directory,
            String schemaText, RunContext ctx,
            no.sikt.graphitron.model.derive.ResolvedKeyProjections.Projections projections) {
        var bundle = buildBundle(schemaText, ctx);
        try (var store = CapturedStore.ofCatalog(directory, CapturedStore.GRAPH, schemaText,
                new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()), classpathCensus(ctx))) {
            return no.sikt.graphitron.plan.EmitPlan.produce(bundle.model(), bundle.federationLink(),
                bundle.usesOneOf(), ctx.outputPackage(), projections,
                new no.sikt.graphitron.model.read.StoreHandle(store.dsl(), CapturedStore.GRAPH));
        }
    }

    /**
     * The {@code *Fetchers} classes a run emits for {@code schemaText}, rendered through its own
     * store-backed plan.
     *
     * <p>The arm to reach for whenever the emission under test is one the plan dispatches on by row
     * presence. {@code TypeFetcherGenerator.generate(schema, outputPackage)} plans no store, so any
     * relation read from one comes back empty there, and a coordinate the classifier still calls a
     * routine write then meets a dispatch with no row to render from. That is the generator's own
     * drift guard firing, correctly, on a fixture that simply never opened a store.
     */
    public static java.util.List<no.sikt.graphitron.javapoet.TypeSpec> storeBackedFetchers(
            java.nio.file.Path directory, String schemaText) {
        return storeBackedFetchers(directory, schemaText, TestConfiguration.testContext());
    }

    /** {@link #storeBackedFetchers(java.nio.file.Path, String)} under a context the caller names. */
    public static java.util.List<no.sikt.graphitron.javapoet.TypeSpec> storeBackedFetchers(
            java.nio.file.Path directory, String schemaText, RunContext ctx) {
        var bundle = buildBundle(schemaText, ctx);
        var plan = storeBackedPlan(directory, schemaText, ctx);
        return no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator.generate(
            bundle.model(), bundle.assembled(), ctx.outputPackage(), plan.launchers(),
            plan.typeUnits().fetchers(), plan.typeUnits().errorFetchers(), plan.routineWrites(),
            plan.keyProjections());
    }

    /**
     * The classpath census capture reads the {@code jvm_} families from, scanned off the test
     * classes' own root: what a rule reading a class's declared form states is only worth
     * something when the classes it read are real ones.
     */
    public static java.util.List<no.sikt.graphitron.model.classpath.CompletionData.ExternalReference>
            classpathCensus(RunContext ctx) {
        try {
            var root = java.nio.file.Path.of(TestSchemaHelper.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            return no.sikt.graphitron.model.classpath.ClasspathScanner.scan(root, ctx.jooqPackage());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("the test classes are not on a file path", e);
        }
    }

    /**
     * The node predicate over the default test context's jOOQ catalog. Test sites that drive
     * {@link SchemaReachability}, the fact traversal, or
     * {@link no.sikt.graphitron.model.schema.federation.KeyNodeSynthesiser} directly, rather than
     * through {@link GraphitronSchemaBuilder}, pass this so their seed set matches production's.
     */
    public static NodeDeclaration nodeDeclaration() {
        return nodeDeclaration(TestConfiguration.testContext());
    }

    /** {@link #nodeDeclaration()} against a caller-supplied context (a fixture jOOQ package). */
    public static NodeDeclaration nodeDeclaration(RunContext ctx) {
        return new NodeDeclaration(new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()));
    }

    /**
     * The attribution pipeline production runs, for tests that need the two handles it hands back
     * rather than a bare parse: {@link AttributedRegistry#registry()} after the synthesis rewrites
     * and {@link AttributedRegistry#preSynthesisRegistry()} before them. A test that reconstructs
     * the stage order itself instead of calling this pins its own reading of the pipeline rather
     * than the pipeline.
     */
    public static AttributedRegistry attributedRegistry(RunContext ctx) {
        return AttributedRegistry.load(ctx);
    }

    /**
     * Parses {@code schemaText} into a {@link TypeDefinitionRegistry} after prepending the
     * directives prelude and the Relay {@code Node} interface (when not already declared).
     * Exposed so sibling test helpers (e.g. snapshot builders for the classification
     * projection truth-table) can drive the same parser without round-tripping through
     * {@code buildBundle}.
     */
    public static TypeDefinitionRegistry parseRegistryWithPrelude(String schemaText) {
        return new SchemaParser().parse(prelude(schemaText) + schemaText);
    }

    private static String prelude(String schemaText) {
        String out = DIRECTIVES + "\n";
        if (!schemaText.contains("interface Node")) {
            out += NODE_INTERFACE;
        }
        return out;
    }

    /**
     * Number of lines the inline-test prelude prepends to {@code schemaText} before
     * graphql-java parses the combined document. Tests that pin a concrete
     * {@code SourceLocation.getLine()} against a fixture add this offset to the
     * carrier field's user-relative line number.
     */
    public static int preludeLineCount(String schemaText) {
        String p = prelude(schemaText);
        int n = 0;
        for (int i = 0; i < p.length(); i++) if (p.charAt(i) == '\n') n++;
        return n;
    }

    private static String loadDirectives() {
        try (InputStream is = SchemaLoader.class.getResourceAsStream("directives.graphqls")) {
            if (is == null) throw new IllegalStateException("directives.graphqls not found on classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
