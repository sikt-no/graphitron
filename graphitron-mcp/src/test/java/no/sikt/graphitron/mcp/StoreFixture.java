package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.RunawayRelation;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.FactWriters;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.catalog.ClasspathScanner;
import no.sikt.graphitron.rewrite.catalog.CompletionData;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A booted fact store with a graph captured into it, for the tools whose answer is a census read.
 *
 * <p>A local layer over {@link CapturedStore}, which is the reactor's capture level: every factory
 * here is one of that level's arms with this module's own vocabulary in front of it, a generated jOOQ
 * package instead of a {@link JooqCatalog} and this module's class census instead of a list the caller
 * assembles. What stays local is what is local: the two generated packages, the placeholder SDL, the
 * fixture census and its walked source root, and the reader a tool's several queries go through.
 *
 * <p>{@link StoreBackedBuild} beside this one runs the whole generator, because the rows its tests
 * read are written by loaders that consume the walk's own streams; a census read needs none of that,
 * and paying for a build to get one prices the generator into every catalog case. What this costs is a
 * schema parse and a walk over the generated jOOQ model.
 *
 * <p>Rows still arrive only through {@link FactCapture}, so a fixture cannot encode a census capture
 * would never write. That is the property that matters rather than which entry point produced it: a
 * hand-inserted row can spell an ordinal or a comment the walk never spells, where a real walk over the
 * real generated model spells what a consumer's own editor would be reading.
 *
 * <p>In memory, so the store dies with the fixture and nothing lands on disk.
 */
public final class StoreFixture implements AutoCloseable {

    /**
     * The graph a fixture captures under unless a test names a second one, which is the capture
     * level's own default rather than a second spelling of it.
     */
    public static final String GRAPH = CapturedStore.GRAPH;

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

    private final CapturedStore captured;
    private StoreReader reader;

    private StoreFixture(CapturedStore captured) {
        this.captured = captured;
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

    /**
     * The catalog axis in this module's terms: a generated package name, or {@code null} for the
     * capture that has no catalog to reach at all.
     */
    private static StoreFixture ofJooqPackage(Path directory, String sdl, String jooqPackage) {
        return new StoreFixture(jooqPackage == null
            ? CapturedStore.of(directory, sdl)
            : CapturedStore.ofCatalog(directory, sdl, new JooqCatalog(jooqPackage)));
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
        var fixture = new StoreFixture(
            CapturedStore.of(directory, GRAPH, PLACEHOLDER_SDL, codeFixtureCensus()));
        FactWriters.refreshJavaSources(fixture.captured.dsl(), List.of(codeFixtureSources()));
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
        return new StoreFixture(CapturedStore.ofCatalog(directory, GRAPH, sdl,
            new JooqCatalog(JOOQ_PACKAGE), codeFixtureCensus()));
    }

    /**
     * A graph whose newest read refused something: the first source parses and is transcribed, the
     * second is spelled so a stage objects to it, whether the parser (a source it cannot read) or the
     * registry (a declaration it will not admit beside the first one's).
     *
     * <p>The state the {@code Previous} freshness axis is about, reached the way an author reaches it,
     * by leaving a file mid-edit. The capture level carries the arm; what is this module's is the
     * catalog it captures against, so a tool answering from the surviving source's coordinates has a
     * census under it as it would in a consumer's own session.
     */
    public static StoreFixture ofRefusedSchema(Path directory, String sdl, String refusedSdl) {
        return new StoreFixture(CapturedStore.ofRefusedSchema(directory, sdl, refusedSdl,
            new JooqCatalog(JOOQ_PACKAGE)));
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
     * Captures this graph again over a different generated model, the way a dev session's next build
     * would after the consumer pointed their codegen somewhere else. The graph's source membership is
     * graph-keyed and rewritten whole, so what the graph's scope sees afterwards is the new catalog
     * alone; the previous package's own rows stay where they are, being another partition, which is
     * the arrangement that lets two modules' catalogs share a store.
     *
     * @param jooqPackage the generated model to capture, or {@code null} for none
     */
    public StoreFixture recaptureCatalog(String jooqPackage) {
        if (jooqPackage == null) {
            captured.recapture(PLACEHOLDER_SDL);
        } else {
            captured.recaptureCatalog(PLACEHOLDER_SDL, new JooqCatalog(jooqPackage));
        }
        return this;
    }

    /** Captures a second graph into this same store, for the cases whose subject is one graph's scope. */
    public StoreFixture andGraph(String otherGraph, String jooqPackage) {
        if (jooqPackage == null) {
            captured.andGraph(otherGraph, PLACEHOLDER_SDL);
        } else {
            captured.andCatalogGraph(otherGraph, PLACEHOLDER_SDL, new JooqCatalog(jooqPackage));
        }
        return this;
    }

    /** The graph this fixture captured under. */
    public String graphName() {
        return captured.graphName();
    }

    /** The scoped query surface a single-query tool takes. */
    public StoreHandle handle() {
        return new StoreHandle(captured.dsl(), captured.graphName());
    }

    /** The same store seen as another graph, for asserting one graph cannot read another's rows. */
    public StoreHandle handleFor(String otherGraph) {
        return new StoreHandle(captured.dsl(), otherGraph);
    }

    /**
     * The reader a tool whose answer is several queries takes, minted on first use and closed with this
     * fixture, which is the dev session's arrangement with no session in play. One per fixture rather
     * than one per call, reads through a reader serializing.
     */
    public StoreReader reader() {
        if (reader == null) {
            reader = captured.reader();
        }
        return reader;
    }

    /**
     * A reader under a stated budget, minted fresh rather than memoized: a case whose subject is the
     * budget wants the reader it named, not whichever one an earlier call cached. The caller closes it.
     */
    public StoreReader reader(ReadBudget budget) {
        return captured.reader(budget);
    }

    /**
     * Makes every read of {@code relation} non-terminating, so a bounded reader touching it runs out
     * of budget through the real query rather than through a threshold a case picked.
     * {@link RunawayRelation} carries the reasoning.
     */
    public void makeRunaway(String relation) {
        RunawayRelation.install(captured.dsl(), relation);
    }

    @Override
    public void close() {
        if (reader != null) {
            reader.close();
        }
        captured.close();
    }
}
