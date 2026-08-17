package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaInputAttribution;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;

import java.io.IOException;
import java.io.UncheckedIOException;
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
        fixture.capture(sdl, jooqPackage, false);
        return fixture;
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
        capture(PLACEHOLDER_SDL, jooqPackage, true);
        return this;
    }

    /** Captures a second graph into this same store, for the cases whose subject is one graph's scope. */
    public StoreFixture andGraph(String otherGraph, String jooqPackage) {
        capture(otherGraph, PLACEHOLDER_SDL, jooqPackage, false);
        return this;
    }

    private void capture(String sdl, String jooqPackage, boolean warm) {
        capture(graphName, sdl, jooqPackage, warm);
    }

    private void capture(String graph, String sdl, String jooqPackage, boolean warm) {
        Path file = write(graph, sdl);
        var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(file)));
        var attribution = SchemaInputAttribution.build(List.of(SchemaInput.file(file)));
        FactCapture.capture(store.dsl(), warm,
            new FactCapture.GraphIdentity(graph, directory), FactCapture.SubjectConfig.none(),
            registry, attribution,
            jooqPackage == null ? null : new JooqCatalog(jooqPackage),
            List.of(), new NodeDeclaration(null));
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
