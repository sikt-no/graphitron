package no.sikt.graphitron.rewrite;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.SdlVerdicts;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaInputAttribution;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import org.jooq.DSLContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A booted fact store with one or more SDL fixtures captured into it: the capture-level population,
 * for the tests whose subject is what a real capture writes.
 *
 * <p><b>Which harness is this.</b> Rows arrive here only through {@link FactCapture}, so a fixture
 * cannot encode a state capture never produces. That is the property to want when the subject is
 * this module's own code, the crawlers and the writers, or agreement between a store-native relation
 * and the transitional walk. When the subject is instead what a relation <em>returns given rows</em>,
 * a view's joins or a check constraint's boundary, the store's own module owns that question and
 * {@link no.sikt.graphitron.model.test.SeededStore} states the inputs as rows without a pipeline in
 * the way. Seeding above the model line skips the step these modules exist to perform, so a fixture
 * here that hand-inserts rows owes a reason at the call site.
 *
 * <p><b>Layered.</b> {@link #withCapturedStore} is the closure form and the shortest thing to type;
 * this handle is the primitive underneath it, for a test that needs more than one step against the
 * open store; and {@link #registryOf} / {@link #attributionOf} / {@link #fixtureFile} / {@link #graph}
 * are the primitives under that, for a test whose axis combination no factory names and which drives
 * {@link FactCapture#capture} itself.
 *
 * <p><b>Named arms, not flags.</b> Each factory says in its own name what its shape carries. The
 * classpath census is an argument rather than an axis, because it pairs with every shape and naming
 * it would double the set to say nothing.
 *
 * <p>Owns the store's lifetime so a test can query after capture, which is the one thing
 * {@link FactCapture#run} deliberately does not allow: in the pipeline the store dies with the pass,
 * because nothing is meant to read it yet. The store itself comes from
 * {@link FactStores#inMemory()} rather than being booted here, so the module that declares the
 * schema is also the module that says how it is stood up.
 */
public final class CapturedStore implements AutoCloseable {

    /**
     * The graph a fixture captures under unless a test names a second one. Both downstream fixtures
     * arrived at this same value independently, and it is also what keys the fixture's filename, so
     * the default spelling on disk is {@code fixture.graphqls}.
     */
    public static final String GRAPH = "fixture";

    private final GraphitronModelStore store;
    private final String graphName;
    private final Path directory;
    private final Path file;
    private final TypeDefinitionRegistry registry;
    private final AttributedRegistry attributed;

    private CapturedStore(GraphitronModelStore store, String graphName, Path directory, Path file,
                          TypeDefinitionRegistry registry, AttributedRegistry attributed) {
        this.store = store;
        this.graphName = graphName;
        this.directory = directory;
        this.file = file;
        this.registry = registry;
        this.attributed = attributed;
    }

    // ---------------------------------------------------------------------------------------
    // The closure form: hand it SDL, get a DSLContext, assert.
    // ---------------------------------------------------------------------------------------

    /** Captures {@code sdl} under {@link #GRAPH} and runs {@code body} against the open store. */
    public static void withCapturedStore(Path directory, String sdl, Consumer<DSLContext> body) {
        withCapturedStore(directory, GRAPH, sdl, body);
    }

    /** The same under a graph the caller names, for the cases whose subject is the partition. */
    public static void withCapturedStore(Path directory, String graphName, String sdl,
                                         Consumer<DSLContext> body) {
        try (var store = of(directory, graphName, sdl)) {
            body.accept(store.dsl());
        }
    }

    // ---------------------------------------------------------------------------------------
    // The handle: one factory per capture shape.
    // ---------------------------------------------------------------------------------------

    /** Captures {@code sdl} alone: the shape for the arms answered by SDL-derived facts. */
    public static CapturedStore of(Path directory, String sdl) {
        return of(directory, GRAPH, sdl);
    }

    /** The same under a graph the caller names, which is what a partition assertion needs two of. */
    public static CapturedStore of(Path directory, String graphName, String sdl) {
        return of(directory, graphName, sdl, List.of());
    }

    /**
     * The same plus a classpath census, for the arms that read the {@code jvm_} families. The census
     * is the caller's own scan rather than a shape of this handle: what a rule reading a class's
     * declared form is worth depends on the classes being real ones.
     */
    public static CapturedStore of(Path directory, String graphName, String sdl,
                                   List<CompletionData.ExternalReference> census) {
        return openAndCapture(directory, graphName, sdl, null, census);
    }

    /**
     * Captures two documents into one graph, which is what a graph assembled from more than one
     * schema file looks like: one parse over both, one capture, two source memberships.
     * {@link #file()} is the first, so a case asks from that document and answers out of the other.
     *
     * <p>Two rather than a list, because two is what the shape claims: a graph whose answer lives in
     * a file other than the one the question came from. A third document repeats the claim.
     */
    public static CapturedStore ofFiles(Path directory, String firstName, String firstSdl,
                                        String secondName, String secondSdl) {
        List<Path> files = List.of(write(directory, firstName, firstSdl),
            write(directory, secondName, secondSdl));
        var registry = RewriteSchemaLoader.load(files.stream().map(SchemaSource::file).toList());
        var store = FactStores.inMemory();
        captureFiles(store, files, directory, GRAPH, registry, null, List.of(), false);
        return new CapturedStore(store, GRAPH, directory, files.getFirst(), registry, null);
    }

    /**
     * Captures {@code sdl} against a generated jOOQ catalog: the shape for the arms whose answer
     * involves a table, a column or a key, none of which a schema alone declares.
     *
     * <p>The catalog is capture's only catalog-shaped input, so there is no inference axis to arm.
     * Nodehood is derived from the captured facts of both corpora rather than decided during the
     * walk, so a bare arm above differs from this one only in whether the catalog facts are in the
     * store to derive from; what the derivation makes of them is
     * {@link no.sikt.graphitron.rewrite.derive.NodeTypeShadowTest}'s subject and not an axis a
     * fixture arms.
     */
    public static CapturedStore ofCatalog(Path directory, String sdl, JooqCatalog jooq) {
        return ofCatalog(directory, GRAPH, sdl, jooq);
    }

    /** {@link #ofCatalog(Path, String, JooqCatalog)} under a graph the caller names. */
    public static CapturedStore ofCatalog(Path directory, String graphName, String sdl, JooqCatalog jooq) {
        return ofCatalog(directory, graphName, sdl, jooq, List.of());
    }

    /** The catalog shape plus a classpath census, for a test whose arms span both. */
    public static CapturedStore ofCatalog(Path directory, String graphName, String sdl, JooqCatalog jooq,
                                          List<CompletionData.ExternalReference> census) {
        return openAndCapture(directory, graphName, sdl, Objects.requireNonNull(jooq, "jooq"), census);
    }

    /**
     * Captures through the attribution pipeline production runs, taking the handle production
     * captures. The difference matters wherever a rewrite stands between the parse and the capture:
     * a bare parse would let capture's macro expansion mint what the rewrite has already put there in
     * the pipeline, and the store would agree with the model for the wrong reason.
     * {@link #attributed()} exposes both handles so a test can compare the two stages.
     *
     * <p>The marked name is the whole of the claim: everything above takes a bare
     * {@link RewriteSchemaLoader#load} registry, so which registry a fixture derived its rows from is
     * what a {@code grep} for this name separates on.
     *
     * <p>The catalog reaches capture, so a rule reading both corpora answers here the way it answers
     * in production. This arm used to capture no catalog while handing the walk a catalog-bearing
     * nodehood predicate, which was the shape that let a fixture disagree with production about
     * nodehood without any assertion noticing.
     */
    public static CapturedStore ofPipeline(Path directory, String sdl) {
        return ofPipeline(directory, sdl, null);
    }

    /**
     * {@link #ofPipeline(Path, String)} with a tag on the input, so {@code TagLinkSynthesiser}
     * fires and its synthesised source name enters the registry capture walks. The only fixture in
     * the tree that puts that sentinel in front of capture's stamp lookup.
     */
    public static CapturedStore ofPipeline(Path directory, String sdl, String tag) {
        Path file = write(directory, GRAPH, sdl);
        var input = new SchemaInput(SchemaSource.file(file), Optional.ofNullable(tag), Optional.empty());
        var ctx = new RewriteContext(
            List.of(input),
            directory, GRAPH, directory,
            TestConfiguration.DEFAULT_OUTPUT_PACKAGE, TestConfiguration.DEFAULT_JOOQ_PACKAGE);
        var attributed = TestSchemaHelper.attributedRegistry(ctx);
        var store = FactStores.inMemory();
        FactCapture.capture(store.dsl(), graph(directory), FactCapture.SubjectConfig.none(),
            attributed.preSynthesisRegistry(), SchemaInputAttribution.build(List.of(input)),
            new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()), List.of());
        return new CapturedStore(store, GRAPH, directory, file, attributed.preSynthesisRegistry(),
            attributed);
    }

    /**
     * A graph whose only source refused: {@code refusedSdl} is spelled so a stage objects to it, and
     * nothing else was read. The shape for a case about what an editor shows in the file the author
     * has just broken, where there is no surviving document to fall back on.
     */
    public static CapturedStore ofRefusedSchema(Path directory, String refusedSdl) {
        return captureRefused(directory, List.of(write(directory, GRAPH, refusedSdl)), null);
    }

    /**
     * A graph whose newest read refused something: {@code sdl} parses and is transcribed, and
     * {@code refusedSdl} is spelled so a stage objects to it, whether the parser (a source it cannot
     * read) or the registry (a declaration it will not admit beside the first one's).
     *
     * <p>The pair with a catalog beside it, where the one-argument arm above is the graph that lost
     * its only source. Both capture a verdict rather than a registry alone, which is what a case
     * about a store whose last read failed has to have in it: the refusal row is what makes the read
     * not-clean, and any surviving source's coordinates are what a reader goes on answering from
     * while it is.
     */
    public static CapturedStore ofRefusedSchema(Path directory, String sdl, String refusedSdl,
                                                JooqCatalog jooq) {
        Objects.requireNonNull(jooq, "jooq");
        return captureRefused(directory, List.of(write(directory, GRAPH, sdl),
            write(directory, GRAPH + "-refused", refusedSdl)), jooq);
    }

    /**
     * Reads {@code files} through {@link RewriteSchemaLoader#parsePerSource} rather than
     * {@code load}, because a refusal is the subject here and {@code load}'s contract is to throw on
     * one, and captures the verdict beside whatever did parse.
     *
     * <p>Fails when nothing objected, so an arm whose refused source quietly started parsing cannot
     * go on passing as a fixture for a refusal.
     */
    private static CapturedStore captureRefused(Path directory, List<Path> files, JooqCatalog jooq) {
        var parse = RewriteSchemaLoader.parsePerSource(files.stream().map(SchemaSource::file).toList());
        if (parse.failures().isEmpty() && parse.registryErrors().isEmpty()) {
            throw new AssertionError("nothing objected to " + files.getLast().getFileName()
                + "; this arm's whole subject is a read that refused something");
        }
        var store = FactStores.inMemory();
        FactCapture.capture(store.dsl(), false, graph(directory), FactCapture.SubjectConfig.none(),
            parse.registry(), new SdlVerdicts(parse.failures(), parse.registryErrors()),
            attributionOfFiles(files), jooq, List.of());
        return new CapturedStore(store, GRAPH, directory, files.getFirst(), parse.registry(), null);
    }

    private static CapturedStore openAndCapture(Path directory, String graphName, String sdl,
                                                JooqCatalog jooq,
                                                List<CompletionData.ExternalReference> census) {
        Path file = write(directory, graphName, sdl);
        var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(file)));
        var store = FactStores.inMemory();
        captureFile(store, file, directory, graphName, registry, jooq, census, false);
        return new CapturedStore(store, graphName, directory, file, registry, null);
    }

    // ---------------------------------------------------------------------------------------
    // Further captures into a store that is already open.
    // ---------------------------------------------------------------------------------------

    /**
     * Captures a second graph, over a schema file of its own, into this same store: the shape for a
     * case asserting that one graph's scope cannot reach another's rows.
     */
    public CapturedStore andGraph(String otherGraph, String sdl) {
        return andGraph(otherGraph, sdl, List.of());
    }

    /** {@link #andGraph(String, String)} with a classpath census on the second graph. */
    public CapturedStore andGraph(String otherGraph, String sdl,
                                  List<CompletionData.ExternalReference> census) {
        captureAnother(otherGraph, sdl, null, census, false);
        return this;
    }

    /** {@link #andGraph(String, String)} against a generated jOOQ catalog. */
    public CapturedStore andCatalogGraph(String otherGraph, String sdl, JooqCatalog jooq) {
        captureAnother(otherGraph, sdl, Objects.requireNonNull(jooq, "jooq"), List.of(), false);
        return this;
    }

    /**
     * Captures a second graph over the <em>same</em> schema file this fixture already captured, which
     * is the shared-file case: one document, two memberships, both true. Keying the filename on the
     * graph name is what leaves this expressible; a directory per graph would make the file's
     * location a function of the graph and could not state it at all.
     */
    public CapturedStore andGraphSharingTheFile(String otherGraph) {
        captureFile(store, file, directory, otherGraph,
            RewriteSchemaLoader.load(List.of(SchemaSource.file(file))), null, List.of(), false);
        return this;
    }

    /**
     * Captures this graph again, the way a dev session's next build does: warm, so the previous
     * round's rows for this graph are stood down before the new ones land rather than accumulating
     * beside them.
     */
    public CapturedStore recapture(String sdl) {
        captureAnother(graphName, sdl, null, List.of(), true);
        return this;
    }

    /**
     * {@link #recapture(String)} against a generated jOOQ catalog, which is a consumer pointing their
     * codegen somewhere else between two builds. The graph's source membership is rewritten whole, so
     * what its scope sees afterwards is the new catalog alone.
     */
    public CapturedStore recaptureCatalog(String sdl, JooqCatalog jooq) {
        captureAnother(graphName, sdl, Objects.requireNonNull(jooq, "jooq"), List.of(), true);
        return this;
    }

    private void captureAnother(String graph, String sdl, JooqCatalog jooq,
                                List<CompletionData.ExternalReference> census, boolean warm) {
        Path other = write(directory, graph, sdl);
        captureFile(store, other, directory, graph,
            RewriteSchemaLoader.load(List.of(SchemaSource.file(other))), jooq, census, warm);
    }

    private static void captureFile(GraphitronModelStore store, Path file, Path directory,
                                    String graphName, TypeDefinitionRegistry registry, JooqCatalog jooq,
                                    List<CompletionData.ExternalReference> census, boolean warm) {
        captureFiles(store, List.of(file), directory, graphName, registry, jooq, census, warm);
    }

    private static void captureFiles(GraphitronModelStore store, List<Path> files, Path directory,
                                     String graphName, TypeDefinitionRegistry registry, JooqCatalog jooq,
                                     List<CompletionData.ExternalReference> census, boolean warm) {
        FactCapture.capture(store.dsl(), warm, new FactCapture.GraphIdentity(graphName, directory),
            FactCapture.SubjectConfig.none(), registry, attributionOfFiles(files), jooq, census);
    }

    // ---------------------------------------------------------------------------------------
    // The primitives, for a test that drives FactCapture itself.
    // ---------------------------------------------------------------------------------------

    /** The graph identity a fixture captured under, shared so readers can scope by it. */
    public static FactCapture.GraphIdentity graph(Path directory) {
        return graph(directory, GRAPH);
    }

    /** {@link #graph(Path)} for a graph the caller names. */
    public static FactCapture.GraphIdentity graph(Path directory, String graphName) {
        return new FactCapture.GraphIdentity(graphName, directory);
    }

    /** Just the parse, for callers that fill a store from something other than the SDL. */
    public static TypeDefinitionRegistry registryOf(Path directory, String sdl) {
        return registryOf(directory, GRAPH, sdl);
    }

    /** {@link #registryOf(Path, String)} writing the fixture under a graph the caller names. */
    public static TypeDefinitionRegistry registryOf(Path directory, String graphName, String sdl) {
        return RewriteSchemaLoader.load(List.of(SchemaSource.file(write(directory, graphName, sdl))));
    }

    /**
     * The attribution map over the one input {@link #registryOf} minted, so capture's stamp lookup
     * resolves the fixture's schema file instead of meeting a name no input declared. Derived from
     * {@link #fixtureFile} like the load is, so the two cannot disagree about what was handed over.
     */
    public static Map<String, SchemaInput> attributionOf(Path directory) {
        return attributionOf(directory, GRAPH);
    }

    /** {@link #attributionOf(Path)} for a graph the caller names. */
    public static Map<String, SchemaInput> attributionOf(Path directory, String graphName) {
        return attributionOfFile(fixtureFile(directory, graphName));
    }

    private static Map<String, SchemaInput> attributionOfFile(Path file) {
        return TestSchemaHelper.attribution(file);
    }

    private static Map<String, SchemaInput> attributionOfFiles(List<Path> files) {
        return TestSchemaHelper.attribution(files.toArray(Path[]::new));
    }

    /**
     * Where a graph's fixture is written; the load and the attribution share it. Keyed on the graph
     * name rather than fixed, because the path a fixture is written to <em>is</em> its identity
     * downstream: it is the string the parser is handed, the one graphql-java echoes back as a source
     * name, and the key capture's stamp lookup is read on. One fixed name would have each capture into
     * a directory silently overwrite the last, the load still succeeding against whatever text landed
     * there.
     */
    public static Path fixtureFile(Path directory) {
        return fixtureFile(directory, GRAPH);
    }

    /** {@link #fixtureFile(Path)} for a graph the caller names. */
    public static Path fixtureFile(Path directory, String graphName) {
        return directory.resolve(graphName + ".graphqls");
    }

    private static Path write(Path directory, String graphName, String sdl) {
        Path file = fixtureFile(directory, graphName);
        try {
            Files.createDirectories(directory);
            Files.writeString(file, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    // ---------------------------------------------------------------------------------------
    // Reads.
    // ---------------------------------------------------------------------------------------

    public DSLContext dsl() {
        return store.dsl();
    }

    public TypeDefinitionRegistry registry() {
        return registry;
    }

    /** The graph this fixture captured under. */
    public String graphName() {
        return graphName;
    }

    /** The schema file this fixture captured, for a reader that needs the name the store spells. */
    public Path file() {
        return file;
    }

    /**
     * A reader of this store, for the cases whose subject is the read boundary rather than a query.
     *
     * <p>Unbounded, and every fixture reader in the reactor says the same thing for the same reason:
     * a harness that named a number would be asserting a wall-clock threshold in a tier that must
     * not fail for being slow. The arm says "no budget" structurally instead.
     */
    public StoreReader reader() {
        return reader(new ReadBudget.Unbounded());
    }

    /** A reader under a stated budget, for the cases whose subject <em>is</em> the budget. */
    public StoreReader reader(ReadBudget budget) {
        return store.reader(budget);
    }

    /** The pipeline's own two handles; null unless this store came from {@link #ofPipeline}. */
    public AttributedRegistry attributed() {
        return attributed;
    }

    @Override
    public void close() {
        store.close();
    }
}
