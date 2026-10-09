package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.RunawayRelation;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.FactWriters;
import no.sikt.graphitron.model.test.ThreadConfinedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.run.ModelCapture;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A booted fact store with a graph captured into it, for the tools whose answer is a census read.
 *
 * <p>A local layer over {@link CapturedStore}, which is the reactor's capture level: every factory
 * here is one of that level's arms with this module's own vocabulary in front of it, a generated jOOQ
 * package instead of a {@link JooqCatalog} and this module's test classes instead of a classpath the
 * caller assembles. What stays local is what is local: the two generated packages, the placeholder
 * SDL, the fixture classes and their walked source root, and the reader a tool's several queries go through.
 *
 * <p>Captures only. The fixture that ran the whole generator moved above this module with the cases
 * that read it, their rows being written by loaders that consume the walk's own streams; a census
 * read needs none of that, and paying for a build to get one would price the generator into every
 * catalog case. What this costs is a schema parse and a walk over the generated jOOQ model.
 *
 * <p>Rows still arrive only through {@link ModelCapture}, so a fixture cannot encode a census
 * capture would never write. That is the property that matters rather than which entry point
 * produced it: a hand-inserted row can spell an ordinal or a comment the reading never spells,
 * where a real reading of the real generated model spells what a consumer's own editor would be
 * reading.
 *
 * <p>In memory, so the store dies with the fixture and nothing lands on disk.
 *
 * <p>Borrows by default: the ordinary arms capture into the store the test thread keeps and clears
 * between borrows, so a case pays for a truncate rather than a boot, and the next borrow on the
 * thread empties it. A fixture that outlives its case, or one that issues DDL, owns its store
 * instead, through {@link #held()}; and a fixture a class shares across its cases is reachable
 * only from that side, as a {@link Shared} extension.
 */
public final class StoreFixture implements AutoCloseable, FixtureView {

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

    /** Whether this fixture booted its own store rather than borrowing the thread's. */
    private final boolean owned;

    private StoreReader reader;

    private StoreFixture(CapturedStore captured) {
        this(captured, false);
    }

    private StoreFixture(CapturedStore captured, boolean owned) {
        this.captured = captured;
        this.owned = owned;
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

    /** The one code-fixture source root a walk covers; see {@link #codeFixtureSources()}. */
    private static final String WALKED_FIXTURE_PATH = "src/test/java/no/sikt/graphitron/mcp/fixtures/code";

    /**
     * The shape the {@code code} tool's cases read: a reading of this module's test classes, plus
     * the {@code java_} declaration family for the one source root a walk covers.
     *
     * <p>Two inputs on purpose, because the tool joins two families that refresh on independent
     * cadences and the fixtures have to be able to disagree. The reading reaches every fixture class;
     * the walk reaches only the {@code fixtures.code} directory, so the {@code fixtures.library} class
     * is a class the store knows and no source positions, which is what a dependency jar is.
     */
    public static StoreFixture ofCodeFixtures(Path directory) {
        return withCode(new StoreFixture(
            CapturedStore.ofWith(directory, GRAPH, PLACEHOLDER_SDL, codeFixtureClasspath())));
    }

    /** The declaration family {@link #ofCodeFixtures} adds after its capture. */
    private static StoreFixture withCode(StoreFixture fixture) {
        FactWriters.refreshJavaSources(fixture.captured.dsl(), List.of(codeFixtureSources()));
        return fixture;
    }

    /**
     * The shape the {@code schema} tool's cases read: an SDL of the caller's over the single-schema
     * generated model, with this module's test classes read as its classpath.
     *
     * <p>All three inputs, because the tool's entry joins all three. The SDL is what declares the
     * coordinates, the catalog is what a {@code @table} binding and a column match resolve against, and
     * the classpath is what a producer's return and a {@code @condition}'s method resolve against; a
     * fixture missing any one of them makes a whole family of slots silently empty.
     */
    public static StoreFixture ofSchema(Path directory, String sdl) {
        return new StoreFixture(CapturedStore.ofCatalogWith(directory, GRAPH, sdl,
            new JooqCatalog(JOOQ_PACKAGE), codeFixtureClasspath()));
    }

    /**
     * The same shapes on a store of the fixture's own, for a fixture that outlives the case that
     * opened it and for a case that issues DDL through {@link #makeRunaway}. Either would otherwise
     * leave the thread's store cleared under it or reshaped for every later case on the thread.
     * Such a fixture pays for a boot, which is the honest price of a store nobody else may clear.
     */
    public static Held held() {
        return Held.INSTANCE;
    }

    /** The owning arms behind {@link #held()}. */
    public static final class Held {

        private static final Held INSTANCE = new Held();

        private Held() {}

        /** {@link StoreFixture#ofCatalog(Path)} on a store of its own. */
        public StoreFixture ofCatalog(Path directory) {
            return new StoreFixture(CapturedStore.ownStoreOfCatalog(directory, PLACEHOLDER_SDL,
                new JooqCatalog(JOOQ_PACKAGE)), true);
        }

        /** {@link StoreFixture#ofSchema(Path, String)} on a store of its own. */
        public StoreFixture ofSchema(Path directory, String sdl) {
            return new StoreFixture(CapturedStore.ownStoreOfCatalogWith(directory, GRAPH, sdl,
                new JooqCatalog(JOOQ_PACKAGE), codeFixtureClasspath()), true);
        }

        /** {@link StoreFixture#ofCodeFixtures(Path)} on a store of its own. */
        public StoreFixture ofCodeFixtures(Path directory) {
            return withCode(new StoreFixture(
                CapturedStore.ownStoreWith(directory, GRAPH, PLACEHOLDER_SDL, codeFixtureClasspath()),
                true));
        }

        /** {@link #ofCatalog} captured once for a whole test class; see {@link Shared}. */
        public Shared sharedCatalog() {
            return new Shared(this::ofCatalog);
        }

        /** {@link #ofSchema} captured once for a whole test class; see {@link Shared}. */
        public Shared sharedSchema(String sdl) {
            return new Shared(directory -> ofSchema(directory, sdl));
        }

        /** {@link #ofCodeFixtures} captured once for a whole test class; see {@link Shared}. */
        public Shared sharedCodeFixtures() {
            return new Shared(this::ofCodeFixtures);
        }
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
     * This module's test classes as the classpath a run would read: its own build output. A reading
     * rather than hand-built rows because everything the {@code code} tool projects is a classfile
     * fact the store holds verbatim: the descriptors that key an overload, the un-erased
     * {@code org.jooq.Condition} match, the declared type forms off the {@code Signature} attribute,
     * and a record's mandated members.
     */
    static List<ClasspathEntry> codeFixtureClasspath() {
        return List.of(new ClasspathEntry(testClassesRoot(), ClasspathEntry.Origin.PROJECT, null, null));
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
     * from.
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

    @Override
    public String graphName() {
        return captured.graphName();
    }

    @Override
    public StoreHandle handle() {
        return new StoreHandle(captured.dsl(), captured.graphName());
    }

    @Override
    public StoreHandle handleFor(String otherGraph) {
        return new StoreHandle(captured.dsl(), otherGraph);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Minted on first use and closed with this fixture, which is the dev session's arrangement
     * with no session in play. One per fixture rather than one per call, reads through a reader
     * serializing.
     */
    @Override
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
     * {@link RunawayRelation} carries the reasoning. DDL, so only on a {@link #held()} fixture.
     */
    public void makeRunaway(String relation) {
        if (!owned) {
            throw new IllegalStateException("makeRunaway installs a relation, and DDL on the thread's"
                + " store would change the schema every later case on this thread borrows; open this"
                + " fixture through StoreFixture.held()");
        }
        RunawayRelation.install(captured.dsl(), relation);
    }

    @Override
    public void close() {
        if (reader != null) {
            reader.close();
        }
        captured.close();
    }

    /**
     * One captured store for a whole test class, as a JUnit extension, on the model of
     * {@link no.sikt.graphitron.model.test.FactStores#perClass()}: captured before the class's first
     * case and closed after its last, so no case spells the store type and the close cannot be
     * forgotten.
     *
     * <pre>{@code
     * @RegisterExtension
     * static final StoreFixture.Shared CATALOG = StoreFixture.held().sharedCatalog();
     *
     * @Test void aClaim() {
     *     var result = GraphitronMcpServer.catalogTablesResult(CATALOG.handle(), Map.of());
     *     ...
     * }
     * }</pre>
     *
     * <p>Two facts are in play and this ties them together. Ownership is whether the store can be
     * cleared under you: a borrowed store is emptied by the next borrow on the thread, so a fixture
     * outliving its case must own one. Sharing is whether other cases see what a case did to it.
     * This is reachable only from {@link #held()}, so sharing implies ownership by construction.
     *
     * <p>A case reads it through {@link FixtureView}, which keeps the mutators out of reach. That
     * does not stop a write through {@link StoreHandle#dsl()}, so the guarantee is a check instead:
     * the base tables and their row counts are snapshotted after the capture and must be unchanged
     * when the class ends, which catches an insert, a delete and the rename
     * {@link RunawayRelation#install} performs alike. The check fails the class rather than a case,
     * which is as close to the write as an end-of-class check can get; the snapshot is the same
     * census {@link ThreadConfinedStore} verifies its clear with.
     *
     * <p>Each shared fixture captures into its own subdirectory of one temporary directory per
     * class: every shape captures under {@link #GRAPH}, so two in one directory would overwrite each
     * other's schema file.
     */
    public static final class Shared implements BeforeAllCallback, AfterAllCallback, FixtureView {

        private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(Shared.class);

        private final Function<Path, StoreFixture> opener;
        private StoreFixture fixture;
        private List<String> tables;
        private String census;
        private Map<String, Integer> counts;

        private Shared(Function<Path, StoreFixture> opener) {
            this.opener = opener;
        }

        @Override
        public void beforeAll(ExtensionContext context) throws IOException {
            Path root = context.getStore(NAMESPACE)
                .computeIfAbsent(ClassDirectory.class, key -> ClassDirectory.create(), ClassDirectory.class)
                .path();
            open(Files.createTempDirectory(root, "shared-"));
        }

        @Override
        public void afterAll(ExtensionContext context) {
            close();
        }

        /** Captures and snapshots; {@link #beforeAll} with the directory chosen by the caller. */
        void open(Path directory) {
            if (fixture != null) {
                throw new IllegalStateException("this shared fixture is already open");
            }
            fixture = opener.apply(directory);
            var dsl = fixture.captured.dsl();
            tables = ThreadConfinedStore.baseTables(dsl);
            census = ThreadConfinedStore.census(tables);
            counts = ThreadConfinedStore.counts(dsl, census);
        }

        /** Checks the store is as captured, then closes it whatever the check found. */
        void close() {
            if (fixture == null) {
                return;
            }
            try {
                verifyUnchanged();
            } finally {
                fixture.close();
                fixture = null;
            }
        }

        /**
         * Fails if a case changed the store: a table created, dropped or renamed, or a row count
         * that moved. Run by {@link #afterAll}, and reachable on its own so the check itself can be
         * pinned.
         */
        void verifyUnchanged() {
            var dsl = fixture().captured.dsl();
            var now = ThreadConfinedStore.baseTables(dsl);
            if (!now.equals(tables)) {
                var created = new ArrayList<>(now);
                created.removeAll(tables);
                var dropped = new ArrayList<>(tables);
                dropped.removeAll(now);
                throw new IllegalStateException(("a case changed the tables of a store its class"
                    + " shares: created %s, dropped %s. Every later case in the class reads a"
                    + " different schema; a case that needs DDL wants a store of its own, from"
                    + " StoreFixture.held().").formatted(created, dropped));
            }
            var found = ThreadConfinedStore.counts(dsl, census);
            var changed = counts.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(found.get(entry.getKey())))
                .map(entry -> "%s (%d rows, captured with %d)".formatted(
                    entry.getKey(), found.get(entry.getKey()), entry.getValue()))
                .toList();
            if (!changed.isEmpty()) {
                throw new IllegalStateException(("a case changed the rows of a store its class"
                    + " shares: %s. Every later case in the class reads them; a case that writes"
                    + " wants a store of its own.").formatted(changed));
            }
        }

        private StoreFixture fixture() {
            if (fixture == null) {
                throw new IllegalStateException("the shared fixture is not open; declare it"
                    + " @RegisterExtension on a static field");
            }
            return fixture;
        }

        @Override
        public String graphName() {
            return fixture().graphName();
        }

        @Override
        public StoreHandle handle() {
            return fixture().handle();
        }

        @Override
        public StoreHandle handleFor(String otherGraph) {
            return fixture().handleFor(otherGraph);
        }

        @Override
        public StoreReader reader() {
            return fixture().reader();
        }
    }

    /**
     * The temporary directory a class's shared fixtures capture under, kept in the class's
     * extension store so JUnit deletes it once the class and every {@code afterAll} are done.
     */
    private record ClassDirectory(Path path) implements AutoCloseable {

        static ClassDirectory create() {
            try {
                return new ClassDirectory(Files.createTempDirectory("graphitron-mcp-shared-"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() throws IOException {
            try (Stream<Path> walk = Files.walk(path)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }
}
