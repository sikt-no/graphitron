package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.boot.StoreUse;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.run.CapturedSchema;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.jooq.DSLContext;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The fact documents' graphs, each captured once per test run and read by every class that names
 * it, through a connection that can only select.
 *
 * <p>A test states the schema it reads as a fact document under {@code src/test/resources/facts}
 * and reads that document's graph from here rather than capturing inline SDL for itself. A document
 * is named by its {@linkplain FactDocuments#path path}, and its graph is captured the first time any
 * class asks for it, by running its {@link Scenario} into {@link CapturedStore#captureFolder}, the one reading of a document, against
 * the catalog the document names and the classpath corpus, or the classpath fixture it names. A class
 * declares the population as an extension and reads inside a transaction:
 *
 * <pre>{@code
 * @RegisterExtension
 * static final FactGraphs.Reader FACTS = FactGraphs.reader();
 *
 * @Test void aCase() {
 *     var rows = FACTS.read("scenarios/field-key-row", graph -> FrameQuery.read(graph.handle()));
 *     ...
 * }
 * }</pre>
 *
 * <p><b>Rows above, not beside, {@link FactStores}.</b> That class never puts rows in a store, which
 * every harness above it relies on; this one exists to. It opens its stores through
 * {@link FactStores#fileBacked(Path, StoreUse)}, so they are counted with every other boot. They are
 * on disk because they accumulate graphs, every document's and every inline schema's of the run, and
 * a graph's rows on disk cost the heap nothing beyond H2's page cache; what an open store does cost
 * the heap, its schema and its statement cache, is why there are few of them.
 *
 * <p><b>Shards.</b> A store has one writing connection, and a capture holds it for its whole
 * transaction, so one store for every graph would serialize every capture in a run. Graphs are
 * spread over {@value #SHARDS_PER_CATALOG} stores per catalog and classpath, a graph's store chosen by
 * its name. Graphs share a store only when they share a catalog and a classpath, the
 * {@linkplain ClasspathFixtures fixture} their document names or the corpus: a relation keyed by no
 * graph (the catalog and classpath families) holds every row its store's captures wrote, so keeping
 * one catalog and one classpath per store keeps those rows the same in every store a graph can land
 * in, whichever classes ran first.
 * The count is a constant rather than read from {@code junit-platform.properties}, which a module
 * may or may not see.
 *
 * <p><b>Read-only.</b> A reader connects as the store's select-only database user, so a write from a
 * test is refused where it is issued. A test whose subject needs to write keeps a store of its own,
 * and captures its document into it by running its {@link Scenario} into
 * {@link CapturedStore#captureFolder}. A test that only counts what a read costs reads the
 * population through a handle whose configuration counts the statements it executes.
 *
 * <p><b>Inline schemas.</b> Inline SDL is deprecated: a test's schema becomes a fact document. Until
 * it has, a test hands the SDL to {@link Reader#readInline}, and a helper to {@link #inline()}. The
 * graph goes in under a name of its own, into the store a document of the same catalog and classpath
 * would land in, so inline schemas cost no stores beyond the documents' own; it is kept apart from
 * the documents' graphs, which the population's checks iterate. The capture and the read hold the
 * store's monitor, so two inline schemas never share its writing connection at once. A store an
 * inline schema boots first takes a {@linkplain #BALLAST ballast graph} before it, so a read that
 * forgets to filter on its graph meets rows that are not its own on every run. The system property
 * {@value #INLINE_PROPERTY} set to {@code false} captures each inline schema into a fresh store
 * instead, for a measurement.
 *
 * <p><b>Lifetime.</b> One population per test JVM, so a static helper with no extension context of
 * its own reaches the same stores a class does, through {@link #inline()}. The first class that
 * registers a {@link Reader} puts it in the root {@link ExtensionContext}'s store, which closes it
 * when the run ends, and with them their files. Each store's file lives in this JVM's own directory
 * under the module's {@code target}, so concurrent test JVMs never share one, and a population sweeps
 * away the directories of JVMs that are no longer running.
 */
public final class FactGraphs implements AutoCloseable {

    /** How many stores each catalog and classpath's graphs are spread over. */
    static final int SHARDS_PER_CATALOG = 4;

    /** The system property that, set to {@code false}, captures each inline schema into a fresh store. */
    static final String INLINE_PROPERTY = "graphitron.inlineShards";

    /**
     * The graph every inline store holds before its first test's, so an unscoped read sees rows of
     * another graph on every run rather than only when another test happened to capture first.
     */
    static final String BALLAST = "ballast";

    private static final String BALLAST_SDL = """
        type Query { ballast: Ballast ballasts: [Ballast] }
        type Ballast { ballastField: String ballastKind: BallastKind }
        enum BallastKind { BALLAST }
        input BallastInput { ballastField: String }
        """;

    private static final AtomicLong INLINE = new AtomicLong();

    /**
     * How many distinct statement texts each store's query statistics keep. H2 drops the least
     * recently run past this, so it stands well above what a store runs in a whole population: one
     * capture runs some 560, and a store of some seventy graphs, its claims' and readers'
     * statements beside its captures', some 1200.
     */
    public static final int STATEMENT_TEXTS = 10_000;

    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(FactGraphs.class);

    private static final AtomicLong BOOTS = new AtomicLong();

    /** Every scenario, by path; building blocks are pulled in rather than run. */
    private final Map<String, CorpusDocuments.Document> documents;

    private final CatalogInput fixture;
    private final List<ClasspathEntry> classpath;
    private final Path directory;
    /** This JVM's store files, one directory per store. */
    private final Path stores;
    private final Map<ShardKey, Shard> shards = new ConcurrentHashMap<>();

    private record ShardKey(Optional<String> catalog, Optional<String> classpath, int index) {}


    private FactGraphs() {
        var plain = new LinkedHashMap<String, CorpusDocuments.Document>();
        FactDocuments.claimed().forEach((document, claim) ->
            plain.put(FactDocuments.path(claim, document), document));
        this.documents = Map.copyOf(plain);
        this.fixture = CatalogInput.of(TestRunContext.of());
        this.classpath = ClasspathCorpus.entries();
        // Under the module's own build output, and kept: a failing scenario's rendered folder is
        // where its expanded schema is read.
        this.directory = Path.of("target", "fact-scenarios").toAbsolutePath();
        var homes = directory.resolve("stores");
        sweepStoreHomes(homes);
        this.stores = homes.resolve(Long.toString(ProcessHandle.current().pid()));
    }

    /** The JVM's population, while it is open. */
    private static FactGraphs shared;

    /** The JVM's population, opened by the first class or helper that asks for it. */
    private static synchronized FactGraphs shared() {
        if (shared == null) {
            shared = new FactGraphs();
        }
        return shared;
    }

    /** The run's population, registered with the root context so the run's end closes it. */
    static FactGraphs of(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE)
            .computeIfAbsent(FactGraphs.class, key -> shared(), FactGraphs.class);
    }

    /**
     * The population's inline route for a caller with no extension context, a static test helper
     * capturing the schema a test hands it: the same pooled stores {@link Reader#readInline} uses.
     */
    public static Inline inline() {
        return new Inline(shared());
    }

    /** The JVM's population, for a harness beside this one that lends from it. */
    static FactGraphs population() {
        return shared();
    }

    /**
     * A whole run's inline schema, captured as a goal captures {@code ctx} into the store an inline
     * schema of its catalog and classpath lands in, under a graph name of its own, and read through
     * that store's select-only reader. For {@link CapturedGraphs#read}.
     */
    <T> T readRun(RunContext ctx, Function<RunGraph, T> body) {
        var graphName = "run-" + INLINE.incrementAndGet();
        var catalog = ctx.jooqPackage() == null ? null : CatalogInput.of(ctx);
        return holding(inlineKeyOf(graphName, catalog, ctx.classpathRoots()), shard -> {
            var schema = shard.ensureRun(new GraphIdentity(graphName, ctx.basedir()), ctx, catalog,
                inlineDirectory(BALLAST + "-" + INLINE.incrementAndGet()));
            return Reader.answer(shard.inlineReader().read(dsl -> body.apply(
                new RunGraph(new StoreHandle(dsl, graphName), schema))));
        });
    }

    /** A class's handle on the population, to register as an extension. */
    public static Reader reader() {
        return new Reader();
    }

    /** How many stores populations have opened in this JVM, for a module accounting for its boots. */
    public static long boots() {
        return BOOTS.get();
    }

    /** Every plain document's path, in folder then name order. */
    Set<String> paths() {
        return new TreeSet<>(documents.keySet());
    }

    /** {@code path}'s graph, captured on first use; the schemas the capture built. */
    private CapturedSchema ensure(String path) {
        var document = documents.get(path);
        if (document == null) {
            throw new IllegalArgumentException("no plain fact document has the path '" + path
                + "'; a path is a folder under facts and a name, as scenarios/field-key-row, and a"
                + " building block is pulled in by a scenario rather than read alone");
        }
        return shardOf(path, document).ensure(path, document);
    }

    private Shard shardOf(String path, CorpusDocuments.Document document) {
        var key = new ShardKey(FactDocuments.catalogPackage(document, fixture),
            FactDocuments.classpathFixture(document), Math.floorMod(path.hashCode(), SHARDS_PER_CATALOG));
        return shards.computeIfAbsent(key, ignored -> new Shard());
    }

    /** Captures every graph in {@code paths} not yet captured, each store's on a thread of its own. */
    void warm(Collection<String> paths) {
        var byShard = new LinkedHashMap<Shard, List<String>>();
        for (var path : paths) {
            var document = documents.get(path);
            if (document == null) {
                ensure(path);
            }
            byShard.computeIfAbsent(shardOf(path, document), ignored -> new ArrayList<>()).add(path);
        }
        try (var pool = Executors.newFixedThreadPool(Math.max(1, byShard.size()))) {
            var pending = new ArrayList<Future<?>>();
            byShard.forEach((shard, members) -> pending.add(pool.submit(() ->
                members.forEach(path -> shard.ensure(path, documents.get(path))))));
            for (var future : pending) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while capturing the fact graphs", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime
                : new IllegalStateException(e.getCause());
        }
    }

    /** How many times {@code path}'s graph has been captured in this population: none, then one. */
    int captures(String path) {
        return shards.values().stream().mapToInt(shard -> shard.captures(path)).sum();
    }

    @Override
    public void close() {
        synchronized (FactGraphs.class) {
            if (shared == this) {
                shared = null;
            }
        }
        shards.values().forEach(Shard::close);
        deleteTree(stores);
    }

    /**
     * Captures {@code sdl} into an inline store for {@code catalog} and {@code classpath} that no
     * other capture holds, under a graph name of its own and the configuration {@code configuration}
     * states, and runs {@code body} over it in one read transaction through that store's own
     * select-only reader, which only the capture holding the store uses.
     */
    <T> T readInline(String stem, String sdl, CatalogInput catalog, List<ClasspathEntry> classpath,
                     SubjectConfig configuration, Function<InlineGraph, T> body) {
        var graphName = "inline-" + INLINE.incrementAndGet();
        var folder = inlineDirectory(graphName);
        if ("false".equals(System.getProperty(INLINE_PROPERTY))) {
            try (var store = FactStores.inMemory(StoreUse.ONE_CAPTURE)) {
                var schema = CapturedStore.captureInline(store.dsl(), folder, stem, graphName, sdl,
                    catalog, classpath, configuration);
                var registry = registryOf(folder, stem);
                try (var reader = store.reader(new ReadBudget.Unbounded())) {
                    return Reader.answer(reader.read(dsl -> body.apply(
                        new InlineGraph(new StoreHandle(dsl, graphName), schema, registry))));
                }
            }
        }
        return holding(inlineKeyOf(graphName, catalog, classpath), shard -> {
            var schema = shard.ensureInline(graphName, folder, stem, sdl, catalog, classpath,
                configuration, inlineDirectory(BALLAST + "-" + INLINE.incrementAndGet()));
            var registry = registryOf(folder, stem);
            return Reader.answer(shard.inlineReader().read(dsl -> body.apply(
                new InlineGraph(new StoreHandle(dsl, graphName), schema, registry))));
        });
    }

    private static TypeDefinitionRegistry registryOf(Path folder, String stem) {
        return SchemaLoader.load(List.of(SchemaSource.file(CapturedStore.fixtureFile(folder, stem))));
    }

    /**
     * The inline route as {@link #inline()} hands it out, for a static helper: each call captures
     * the SDL it is given into a pooled store and reads it there.
     */
    public static final class Inline {

        private final FactGraphs population;

        private Inline(FactGraphs population) {
            this.population = population;
        }

        /**
         * {@code sdl} captured as {@link Reader#readInline(String, String, CatalogInput, List,
         * Function)} captures it, under the configuration {@code configuration} states.
         */
        public <T> T read(String stem, String sdl, CatalogInput catalog,
                          List<ClasspathEntry> classpath, SubjectConfig configuration,
                          Function<InlineGraph, T> body) {
            return population.readInline(stem, sdl, catalog, classpath, configuration, body);
        }

        /** The same with no configuration stated. */
        public <T> T read(String stem, String sdl, CatalogInput catalog,
                          List<ClasspathEntry> classpath, Function<InlineGraph, T> body) {
            return read(stem, sdl, catalog, classpath, SubjectConfig.none(), body);
        }
    }

    /**
     * The store an inline graph lands in: the shard a document of the same catalog and classpath
     * would, a classpath other than the corpus keyed by its entries rather than by a fixture's name.
     */
    private ShardKey inlineKeyOf(String graphName, CatalogInput catalog, List<ClasspathEntry> entries) {
        var classpathKey = entries.equals(classpath) ? Optional.<String>empty()
            : Optional.of("inline:" + List.copyOf(entries).hashCode());
        // A classpath no document states takes one store, so each adds a single database.
        var index = classpathKey.isEmpty() ? Math.floorMod(graphName.hashCode(), SHARDS_PER_CATALOG) : 0;
        return new ShardKey(Optional.ofNullable(catalog).map(CatalogInput::jooqPackage), classpathKey, index);
    }

    /** How many stores populations in this JVM have retired because they stopped answering. */
    private static final AtomicLong RETIRED = new AtomicLong();

    /** How many stores populations in this JVM have retired, for a test of the retirement. */
    static long retired() {
        return RETIRED.get();
    }

    /**
     * Runs {@code capture} holding {@code key}'s store and, when it fails in the store, retires the
     * store if it no longer answers, so the next capture gets a fresh one rather than meeting the
     * same failure. A database H2 has shut down after running out of memory is the case: every graph
     * it held would fail every read after it. A store that refused one statement and still answers is
     * kept.
     */
    private <T> T holding(ShardKey key, Function<Shard, T> capture) {
        var shard = shards.computeIfAbsent(key, ignored -> new Shard());
        synchronized (shard) {
            try {
                return capture.apply(shard);
            } catch (RuntimeException | Error failure) {
                if (inTheStore(failure) && !shard.answers()) {
                    if (shards.remove(key, shard)) {
                        RETIRED.incrementAndGet();
                    }
                    try {
                        shard.close();
                    } catch (RuntimeException closing) {
                        failure.addSuppressed(closing);
                    }
                }
                throw failure;
            }
        }
    }

    /** Whether {@code failure} came out of the database rather than out of the test's own code. */
    private static boolean inTheStore(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.jooq.exception.DataAccessException
                || cause instanceof java.sql.SQLException || cause instanceof VirtualMachineError) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shuts down the database {@code graphName}'s inline graph is held in, as H2 does when it runs out
     * of memory, for a test of the retirement.
     */
    void shutDownStoreOf(String graphName) {
        for (var shard : shards.values()) {
            if (shard.holdsInline(graphName)) {
                shard.shutDown();
                return;
            }
        }
        throw new IllegalArgumentException("no store holds the inline graph " + graphName);
    }

    /** A fresh directory for one store's file, under this JVM's own. */
    private Path storeHome() {
        try {
            return Files.createTempDirectory(Files.createDirectories(stores), "store-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes the store files of test JVMs that are no longer running. */
    private static void sweepStoreHomes(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> jvms = Files.list(root)) {
            jvms.filter(dir -> {
                    var pid = dir.getFileName().toString();
                    return !pid.chars().allMatch(Character::isDigit)
                        || ProcessHandle.of(Long.parseLong(pid)).isEmpty();
                })
                .forEach(FactGraphs::deleteTree);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Where an inline graph's file is written: kept for the run, since its store outlives the test. */
    private Path inlineDirectory(String graphName) {
        return directory.resolve("inline").resolve(graphName);
    }

    /**
     * What running a scenario found: each claim's block, how the store disagreed with it then, and
     * the behaviours the rows it matched then pin.
     */
    record Verification(String scenario, List<CorpusExpectations.Block> blocks, List<String> divergences,
                        Map<String, java.util.Set<Pins.Pin>> pins) {

        Verification(String scenario) {
            this(scenario, new ArrayList<>(), new ArrayList<>(), new java.util.TreeMap<>());
        }

        /** Checks {@code claim} against the store {@code dsl} writes, as the last capture left it. */
        void check(DSLContext dsl, Scenario.Claim claim) {
            var claimed = CorpusExpectations.declaredBlocks(
                org.jooq.impl.DSL.using(org.jooq.SQLDialect.H2), scenario, claim.sdl());
            blocks.addAll(claimed);
            CorpusExpectations.divergences(dsl, claimed).stream()
                .map(divergence -> "line " + claim.line() + " " + divergence)
                .forEach(divergences::add);
            Pins.merge(pins, Pins.of(dsl, claimed));
        }
    }

    /** What running each scenario captured so far found, by its path. */
    Map<String, Verification> verifications() {
        var all = new java.util.TreeMap<String, Verification>();
        shards.values().forEach(shard -> all.putAll(shard.verifications()));
        return all;
    }

    /** One store and the graphs captured into it, every write under this object's lock. */
    private final class Shard {

        private GraphitronModelStore store;
        private Path home;
        /** Inline graphs held beside the documents', which the population's checks do not iterate. */
        private final Set<String> inlineGraphs = new LinkedHashSet<>();
        private final Map<String, CapturedSchema> graphs = new LinkedHashMap<>();
        private final Map<String, Verification> verifications = new LinkedHashMap<>();
        private final Map<String, Integer> captures = new LinkedHashMap<>();

        synchronized CapturedSchema ensure(String path, CorpusDocuments.Document document) {
            var schema = graphs.get(path);
            if (schema != null) {
                return schema;
            }
            boot();
            // The scenario run: its renders captured into this store as a build reads a folder, under
            // the configuration its own file states, and each claim checked as it arrives.
            var scenario = Scenario.load(path);
            var keyed = FactDocuments.catalogPackage(document, fixture);
            var keyedClasspath = FactDocuments.classpathFixture(document);
            var verification = new Verification(path);
            var built = new CapturedSchema[1];
            var dsl = store.dsl();
            scenario.run(directory.resolve(path), (folder, at, first, stated) -> {
                    var texts = stated.isEmpty() ? List.<String>of() : List.of(stated);
                    var catalog = FactDocuments.StatedConfiguration.catalog(texts, fixture);
                    if (!Optional.ofNullable(catalog).map(CatalogInput::jooqPackage).equals(keyed)) {
                        throw new IllegalArgumentException(path + " changes its catalog between steps;"
                            + " a scenario keeps the catalog its first configuration names");
                    }
                    built[0] = CapturedStore.captureFolder(dsl, folder, path, catalog,
                        FactDocuments.classpath(path, keyedClasspath, stated, classpath),
                        FactDocuments.StatedConfiguration.of(texts),
                        FactDocuments.StatedConfiguration.fileAnnotations(texts), at, !first);
                },
                claim -> verification.check(dsl, claim));
            if (built[0] == null) {
                throw new IllegalStateException(path + " renders nothing to capture");
            }
            schema = built[0];
            graphs.put(path, schema);
            verifications.put(path, verification);
            captures.merge(path, 1, Integer::sum);
            return schema;
        }

        /**
         * Captures {@code sdl} under {@code graphName}, booting the store and capturing the ballast
         * first if this is its first graph. Every inline graph has a name of its own, so nothing is
         * captured twice.
         */
        synchronized CapturedSchema ensureInline(String graphName, Path folder, String stem,
                                                 String sdl, CatalogInput catalog,
                                                 List<ClasspathEntry> classpath,
                                                 SubjectConfig configuration, Path ballastFolder) {
            if (graphs.isEmpty() && inlineGraphs.isEmpty()) {
                boot();
                CapturedStore.captureInline(store.dsl(), ballastFolder, BALLAST, BALLAST, BALLAST_SDL,
                    catalog, classpath);
                inlineGraphs.add(BALLAST);
            }
            var schema = CapturedStore.captureInline(store.dsl(), folder, stem, graphName, sdl,
                catalog, classpath, configuration);
            inlineGraphs.add(graphName);
            return schema;
        }

        /** Captures a whole run's {@code ctx} under {@code graph}, as {@link #ensureInline} does SDL. */
        synchronized CapturedSchema ensureRun(GraphIdentity graph, RunContext ctx, CatalogInput catalog,
                                              Path ballastFolder) {
            if (graphs.isEmpty() && inlineGraphs.isEmpty()) {
                boot();
                CapturedStore.captureInline(store.dsl(), ballastFolder, BALLAST, BALLAST, BALLAST_SDL,
                    catalog, ctx.classpathRoots());
                inlineGraphs.add(BALLAST);
            }
            var schema = GraphitronStore.capture(store, graph, SubjectConfig.of(ctx), ctx.classpathRoots(),
                ctx.codegenLoader());
            inlineGraphs.add(graph.name());
            return schema;
        }

        synchronized boolean holdsInline(String graphName) {
            return inlineGraphs.contains(graphName);
        }

        /** Whether the store still runs a statement. */
        synchronized boolean answers() {
            if (store == null) {
                return true;
            }
            try {
                store.dsl().selectOne().fetch();
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        synchronized void shutDown() {
            store.dsl().execute("SHUTDOWN");
        }

        /** Opens the store on first use, on disk under a home of its own. */
        private void boot() {
            if (store != null) {
                return;
            }
            home = storeHome();
            store = FactStores.fileBacked(home, StoreUse.LONG_LIVED);
            BOOTS.incrementAndGet();
            // Every statement the store runs, captures and reads alike, kept by its text for the
            // checks over what the population's captures ran; room for every distinct text a
            // shard sees, so none is evicted before those checks read it.
            store.dsl().execute("SET QUERY_STATISTICS_MAX_ENTRIES " + STATEMENT_TEXTS);
            store.dsl().execute("SET QUERY_STATISTICS TRUE");
        }

        synchronized Map<String, Verification> verifications() {
            return new LinkedHashMap<>(verifications);
        }

        synchronized int captures(String path) {
            return captures.getOrDefault(path, 0);
        }

        synchronized Set<String> graphs() {
            return new LinkedHashSet<>(graphs.keySet());
        }

        synchronized StoreReader reader() {
            return store.reader(new ReadBudget.Unbounded());
        }

        /** An inline store's own reader, used only by the capture that has borrowed the store. */
        private StoreReader inlineReader;

        synchronized StoreReader inlineReader() {
            if (inlineReader == null) {
                inlineReader = reader();
            }
            return inlineReader;
        }

        synchronized void close() {
            if (inlineReader != null) {
                inlineReader.close();
            }
            if (store != null) {
                store.close();
            }
            if (home != null) {
                deleteTree(home);
            }
        }
    }

    /**
     * One graph as a read sees it: the handle scoped to the graph, valid only inside the read that
     * handed it out, and the two schemas its capture built, which a generating run needs beside the
     * store and which are values a read may keep.
     */
    public record FactGraph(StoreHandle handle, CapturedSchema schema) {}

    /**
     * An inline schema's graph as a read sees it: the handle scoped to the graph, valid only inside
     * the read that handed it out, what the capture built, and the registry of the one file the SDL
     * was written to, which a test comparing the written schema with a derived one reads.
     */
    public record InlineGraph(StoreHandle handle, CapturedSchema schema,
                              TypeDefinitionRegistry registry) {}

    /**
     * A whole run's graph as a read sees it: the handle scoped to the graph, valid only inside the
     * read that handed it out, and what the capture built, which the generator takes beside it.
     */
    public record RunGraph(StoreHandle handle, CapturedSchema schema) {}

    /**
     * A class's handle on the population, registered as an extension so the readers it mints close
     * when the class is done. One reader per store the class reads from, minted on first use: a
     * class's methods run one at a time, so a reader per class never makes two classes queue behind
     * each other.
     *
     * <p>Every read is one transaction ({@link StoreReader#read}), so an answer assembled from
     * several queries sees one state of the store. There is no way to a handle outside one.
     */
    public static final class Reader implements BeforeAllCallback, AfterAllCallback {

        private FactGraphs population;
        private final Map<Shard, StoreReader> readers = new LinkedHashMap<>();

        private Reader() {}

        @Override
        public void beforeAll(ExtensionContext context) {
            population = FactGraphs.of(context);
        }

        @Override
        public synchronized void afterAll(ExtensionContext context) {
            readers.values().forEach(StoreReader::close);
            readers.clear();
        }

        /**
         * Runs {@code body} over {@code path}'s graph in one read transaction, capturing the graph
         * first if no class has yet, and returns what it produced.
         */
        public <T> T read(String path, Function<FactGraph, T> body) {
            var schema = population().ensure(path);
            var shard = population.shardOf(path, population.documents.get(path));
            return answer(readerOf(shard).read(dsl -> body.apply(
                new FactGraph(new StoreHandle(dsl, path), schema))));
        }

        /**
         * Captures {@code sdl} into an inline store for {@code catalog} and {@code classpath} that no
         * other capture holds, under a graph name of its own, and runs {@code body} over it in one
         * read transaction. The schema file is named {@code stem}{@code .graphqls}. A null
         * {@code catalog} is a run that declared no catalog.
         */
        public <T> T readInline(String stem, String sdl, CatalogInput catalog,
                                List<ClasspathEntry> classpath, Function<InlineGraph, T> body) {
            return population().readInline(stem, sdl, catalog, classpath, SubjectConfig.none(), body);
        }

        /** {@link #readInline(String, String, CatalogInput, List, Function)} with no classpath. */
        public <T> T readInline(String stem, String sdl, CatalogInput catalog,
                                Function<InlineGraph, T> body) {
            return readInline(stem, sdl, catalog, List.of(), body);
        }

        /** Captures every graph in {@code paths} not yet captured, in parallel across stores. */
        public void warm(Collection<String> paths) {
            population().warm(paths);
        }

        /** How many times {@code path}'s graph has been captured in this run: none, then one. */
        public int captures(String path) {
            return population().captures(path);
        }

        /** Every plain document's path, in folder then name order. */
        public Set<String> paths() {
            return population().paths();
        }

        /** What running each scenario captured so far found, by its path. */
        Map<String, Verification> verifications() {
            return population().verifications();
        }

        /**
         * Runs {@code body} once per store holding any of the graphs captured so far, in one read
         * transaction each, with the names of the graphs that store holds: for a check over the
         * whole population rather than over one graph.
         */
        public <T> List<T> eachStore(BiFunction<DSLContext, Set<String>, T> body) {
            var results = new ArrayList<T>();
            for (var shard : population().shards.values()) {
                var graphs = shard.graphs();
                if (!graphs.isEmpty()) {
                    results.add(answer(readerOf(shard).read(dsl -> body.apply(dsl, graphs))));
                }
            }
            return results;
        }

        /**
         * Every distinct statement text the population's stores have run, every document captured
         * first: the captures' statements, with the claims' and readers' beside them, as each
         * store's query statistics keep them, binds left as markers. Fails rather than hand back a
         * set a store may have dropped texts from.
         */
        public Set<String> statementTexts() {
            warm(paths());
            var texts = new TreeSet<String>();
            eachStore((dsl, graphs) -> {
                var held = dsl.fetch("SELECT SQL_STATEMENT FROM INFORMATION_SCHEMA.QUERY_STATISTICS")
                    .getValues(0, String.class);
                // H2 drops the least recently run third when the statistics overflow, and a store
                // that has dropped any holds two thirds of the limit at least from then on.
                if (held.size() >= STATEMENT_TEXTS / 2) {
                    throw new IllegalStateException("a store of " + graphs.size() + " graphs ran "
                        + held.size() + " distinct statements, near enough to the limit of "
                        + STATEMENT_TEXTS + " that some may have been dropped; raise the limit");
                }
                texts.addAll(held);
                return null;
            });
            return texts;
        }

        private FactGraphs population() {
            if (population == null) {
                throw new IllegalStateException("FactGraphs.Reader is used before its class started;"
                    + " register it with @RegisterExtension on a static field");
            }
            return population;
        }

        private synchronized StoreReader readerOf(Shard shard) {
            return readers.computeIfAbsent(shard, Shard::reader);
        }

        static <T> T answer(StoreAnswer<T> answer) {
            return switch (answer) {
                case StoreAnswer.Answered<T> answered -> answered.value();
                case StoreAnswer.OutOfBudget<T> out -> throw new IllegalStateException(
                    "an unbounded fact-graph reader ran out of budget on " + out.sql());
            };
        }
    }
}
