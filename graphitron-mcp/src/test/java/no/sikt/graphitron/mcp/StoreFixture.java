package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.capture.JavaSourceFacts;
import no.sikt.graphitron.rewrite.catalog.ClasspathScanner;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.capture.SourceWalker;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaInputAttribution;
import no.sikt.graphitron.rewrite.schema.SdlVerdicts;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A booted fact store with a graph captured into it, for the tools whose answer is a census read.
 *
 * <p>The capture writer, called directly. {@link StoreBackedBuild} beside this one runs the whole
 * generator, because the rows its tests read are written by loaders that consume the walk's own
 * streams; a census read needs none of that, and paying for a build to get one prices the generator
 * into every catalog case. What this costs is a schema parse and a walk over the generated jOOQ model,
 * which the language server's own fixture has been buying by the test for as long as it has read the
 * store.
 *
 * <p>Rows still arrive only through {@link FactCapture}, so a fixture cannot encode a census capture
 * would never write. That is the property that matters rather than which entry point produced it: a
 * hand-inserted row can spell an ordinal or a comment the walk never spells, where a real walk over the
 * real generated model spells what a consumer's own editor would be reading.
 *
 * <p>In memory, so the store dies with the fixture and nothing lands on disk.
 */
public final class StoreFixture implements AutoCloseable {

    /** The graph a fixture captures under unless a test names a second one. */
    public static final String GRAPH = "fixture";

    /**
     * The single-schema generated model: {@code graphitron-sakila-db} generates it with
     * {@code inputSchema=public}, so every table name in its census is unique and a bare spelling
     * resolves.
     */
    public static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    /**
     * The two-schema generated model, which declares {@code event} in both {@code multischema_a} and
     * {@code multischema_b} precisely so an unqualified name reaches two tables. Also the second
     * catalog a test needs when its subject is a census that changed rather than a census.
     */
    public static final String MULTISCHEMA_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.multischemafixture";

    /** SDL for a fixture whose subject is the catalog, so its schema is beside the point. */
    private static final String PLACEHOLDER_SDL = "type Query { placeholder: Int }\n";

    private final GraphitronModelStore store;
    private final String graphName;
    private final Path directory;
    private StoreReader reader;

    private StoreFixture(GraphitronModelStore store, String graphName, Path directory) {
        this.store = store;
        this.graphName = graphName;
        this.directory = directory;
    }

    /** The catalog shape: the single-schema generated model captured under {@link #GRAPH}. */
    public static StoreFixture ofCatalog(Path directory) {
        return ofCatalog(directory, PLACEHOLDER_SDL);
    }

    /** The catalog shape over an SDL of the caller's, for a case whose subject spans both. */
    public static StoreFixture ofCatalog(Path directory, String sdl) {
        return ofJooqPackage(directory, sdl, JOOQ_PACKAGE);
    }

    /** The catalog shape over the two-schema generated model. */
    public static StoreFixture ofMultiSchemaCatalog(Path directory) {
        return ofJooqPackage(directory, PLACEHOLDER_SDL, MULTISCHEMA_JOOQ_PACKAGE);
    }

    /**
     * A capture with no catalog at all, which is the pre-codegen state a consumer is in before their
     * first build: the graph is captured and its census is empty. An answer, not an absence, and the
     * distinction the catalog tools' refusal arm turns on.
     */
    public static StoreFixture withoutCatalog(Path directory) {
        return ofJooqPackage(directory, PLACEHOLDER_SDL, null);
    }

    private static StoreFixture ofJooqPackage(Path directory, String sdl, String jooqPackage) {
        var store = GraphitronModelStore.open();
        var fixture = new StoreFixture(store, GRAPH, directory);
        fixture.capture(sdl, jooqPackage, false, List.of());
        return fixture;
    }

    /** The package the code fixtures live under; the census is narrowed to it. */
    private static final String CODE_FIXTURE_PACKAGE = "no.sikt.graphitron.mcp.fixtures.";

    /** The one code-fixture source root a walk covers; see {@link #codeFixtureSources()}. */
    private static final String WALKED_FIXTURE_PATH = "src/test/java/no/sikt/graphitron/mcp/fixtures/code";

    /** Scanned once; see {@link #codeFixtureCensus()}. */
    private static List<CompletionData.ExternalReference> codeFixtureCensus;

    /**
     * The shape the {@code code} tool's cases read: the census of the fixture classes under
     * {@code no.sikt.graphitron.mcp.fixtures}, plus the {@code java_} declaration family for the one
     * source root a walk covers.
     *
     * <p>Two inputs on purpose, because the tool joins two families that refresh on independent
     * cadences and the fixtures have to be able to disagree. The census reaches every fixture class;
     * the walk reaches only the {@code fixtures.code} directory, so the {@code fixtures.library} class
     * is a class the store knows and no source positions, which is what a dependency jar is.
     */
    public static StoreFixture ofCodeFixtures(Path directory) {
        var store = GraphitronModelStore.open();
        var fixture = new StoreFixture(store, GRAPH, directory);
        fixture.capture(PLACEHOLDER_SDL, null, false, codeFixtureCensus());
        fixture.refreshJavaSources(codeFixtureSources());
        return fixture;
    }

    /**
     * The shape the {@code schema} tool's cases read: an SDL of the caller's over the single-schema
     * generated model, with the fixture classes on the classpath census.
     *
     * <p>All three inputs, because the tool's entry joins all three. The SDL is what declares the
     * coordinates, the catalog is what a {@code @table} binding and a column match resolve against, and
     * the census is what a producer's return and a {@code @condition}'s method resolve against; a
     * fixture missing any one of them makes a whole family of slots silently empty.
     */
    public static StoreFixture ofSchema(Path directory, String sdl) {
        var store = GraphitronModelStore.open();
        var fixture = new StoreFixture(store, GRAPH, directory);
        fixture.capture(sdl, JOOQ_PACKAGE, false, codeFixtureCensus());
        return fixture;
    }

    /**
     * A graph whose newest read refused something: the first source parses and is transcribed, the
     * second is spelled so a stage objects to it, whether the parser (a source it cannot read) or the
     * registry (a declaration it will not admit beside the first one's).
     *
     * <p>The state the {@code Previous} freshness axis is about, reached the way an author reaches it,
     * by leaving a file mid-edit. Both halves have to be real for the axis to mean anything: the
     * refusal row is what makes the read not-clean, and the surviving source's coordinates are what
     * the tools go on answering from while it is.
     *
     * <p>Through {@link RewriteSchemaLoader#parsePerSource} rather than {@code load}, because a
     * refusal is what this fixture is for and {@code load} is the entry point whose contract is to
     * throw on one.
     */
    public static StoreFixture ofRefusedSchema(Path directory, String sdl, String refusedSdl) {
        var store = GraphitronModelStore.open();
        var fixture = new StoreFixture(store, GRAPH, directory);
        fixture.captureRefused(sdl, refusedSdl);
        return fixture;
    }

    private void captureRefused(String sdl, String refusedSdl) {
        List<Path> files = List.of(write(graphName, sdl), write(graphName + "-refused", refusedSdl));
        var parse = RewriteSchemaLoader.parsePerSource(files.stream().map(SchemaSource::file).toList());
        if (parse.failures().isEmpty() && parse.registryErrors().isEmpty()) {
            throw new AssertionError("nothing objected to the second source; this fixture's whole "
                + "subject is a read that refused something");
        }
        FactCapture.capture(store.dsl(), false,
            new FactCapture.GraphIdentity(graphName, directory), FactCapture.SubjectConfig.none(),
            parse.registry(), new SdlVerdicts(parse.failures(), parse.registryErrors()),
            SchemaInputAttribution.build(files.stream().map(SchemaInput::file).toList()),
            new JooqCatalog(JOOQ_PACKAGE), List.of());
    }

    /**
     * The census of the fixture classes as a real classfile scan produced it. A scan rather than
     * hand-built rows because everything the {@code code} tool projects is a classfile fact the store
     * holds verbatim: the descriptors that key an overload, the un-erased {@code org.jooq.Condition}
     * match, the declared type forms off the {@code Signature} attribute, and a record's mandated
     * members. A hand-built reference can spell all of those differently from any real compiler.
     *
     * <p>Scanned once per JVM: the scan reads every class this module compiled and the answer does not
     * change between tests.
     */
    static synchronized List<CompletionData.ExternalReference> codeFixtureCensus() {
        if (codeFixtureCensus == null) {
            codeFixtureCensus = ClasspathScanner.scan(testClassesRoot(), JOOQ_PACKAGE).stream()
                .filter(reference -> reference.className().startsWith(CODE_FIXTURE_PACKAGE))
                .toList();
        }
        return codeFixtureCensus;
    }

    /**
     * The walked source root: this module's own {@code fixtures.code} sources, derived from the
     * compiled-classes root rather than spelled as a build path, so the fixture classes and the sources
     * the family reads are the same code.
     */
    static Path codeFixtureSources() {
        Path root = testClassesRoot().getParent().getParent().resolve(WALKED_FIXTURE_PATH);
        if (!Files.isDirectory(root)) {
            throw new AssertionError("code fixture sources are not where the module layout puts them: " + root);
        }
        return root;
    }

    /**
     * This module's compiled test classes, which is the classpath entry every code fixture is read
     * from. Shared with the fixtures that need a census with real classes on it rather than an empty
     * one.
     */
    static Path testClassesRoot() {
        try {
            return Path.of(StoreFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("test classes root is not a file path", e);
        }
    }

    /**
     * Reads the {@code .java} files under {@code sourceRoot} into this store's {@code java_} family,
     * the way a dev session's source watcher does. The real writer, so a fixture cannot record a
     * declaration shape a parse never produces.
     */
    private void refreshJavaSources(Path sourceRoot) {
        var roots = List.of(sourceRoot);
        new JavaSourceFacts(store.dsl()).refresh(roots, new SourceWalker().walkFiles(roots));
    }

    /**
     * Captures this graph again over a different generated model, the way a dev session's next build
     * would after the consumer pointed their codegen somewhere else. The graph's source membership is
     * graph-keyed and rewritten whole, so what the graph's scope sees afterwards is the new catalog
     * alone; the previous package's own rows stay where they are, being another partition, which is
     * the arrangement that lets two modules' catalogs share a store.
     *
     * @param jooqPackage the generated model to capture, or {@code null} for none
     */
    public StoreFixture recaptureCatalog(String jooqPackage) {
        capture(PLACEHOLDER_SDL, jooqPackage, true, List.of());
        return this;
    }

    /** Captures a second graph into this same store, for the cases whose subject is one graph's scope. */
    public StoreFixture andGraph(String otherGraph, String jooqPackage) {
        capture(otherGraph, PLACEHOLDER_SDL, jooqPackage, false, List.of());
        return this;
    }

    private void capture(
        String sdl, String jooqPackage, boolean warm, List<CompletionData.ExternalReference> classpath
    ) {
        capture(graphName, sdl, jooqPackage, warm, classpath);
    }

    private void capture(
        String graph, String sdl, String jooqPackage, boolean warm,
        List<CompletionData.ExternalReference> classpath
    ) {
        Path file = write(graph, sdl);
        var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(file)));
        var attribution = SchemaInputAttribution.build(List.of(SchemaInput.file(file)));
        FactCapture.capture(store.dsl(), warm,
            new FactCapture.GraphIdentity(graph, directory), FactCapture.SubjectConfig.none(),
            registry, attribution,
            jooqPackage == null ? null : new JooqCatalog(jooqPackage),
            classpath);
    }

    private Path write(String graph, String sdl) {
        Path path = directory.resolve(graph + ".graphqls");
        try {
            Files.writeString(path, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }

    /** The graph this fixture captured under. */
    public String graphName() {
        return graphName;
    }

    /** The scoped query surface a single-query tool takes. */
    public StoreHandle handle() {
        return new StoreHandle(store.dsl(), graphName);
    }

    /** The same store seen as another graph, for asserting one graph cannot read another's rows. */
    public StoreHandle handleFor(String otherGraph) {
        return new StoreHandle(store.dsl(), otherGraph);
    }

    /**
     * The reader a tool whose answer is several queries takes, minted on first use and closed with this
     * fixture, which is the dev session's arrangement with no session in play. One per fixture rather
     * than one per call, reads through a reader serializing.
     */
    public StoreReader reader() {
        if (reader == null) {
            reader = store.reader();
        }
        return reader;
    }

    @Override
    public void close() {
        if (reader != null) {
            reader.close();
        }
        store.close();
    }
}
