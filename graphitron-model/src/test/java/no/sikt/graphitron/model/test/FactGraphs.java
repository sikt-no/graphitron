package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.boot.StoreUse;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.run.CapturedSchema;
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
 * the catalog the document names and the classpath corpus every document is read over. A class
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
 * {@link FactStores#inMemory(StoreUse)}, so they are counted with every other boot.
 *
 * <p><b>Shards.</b> A store has one writing connection, and a capture holds it for its whole
 * transaction, so one store for every graph would serialize every capture in a run. Graphs are
 * spread over {@value #SHARDS_PER_CATALOG} stores per catalog, a graph's store chosen by its name.
 * Graphs share a store only when they share a catalog: a relation keyed by no graph (the catalog
 * and classpath families) holds every row its store's captures wrote, so keeping the catalog one per
 * store keeps those rows the same in every store a graph can land in, whichever classes ran first.
 * The count is a constant rather than read from {@code junit-platform.properties}, which a module
 * may or may not see.
 *
 * <p><b>Read-only.</b> A reader connects as the store's select-only database user, so a write from a
 * test is refused where it is issued. A test whose subject needs to write keeps a store of its own,
 * and captures its document into it by running its {@link Scenario} into
 * {@link CapturedStore#captureFolder}. A test that only counts what a read costs reads the
 * population through a handle whose configuration counts the statements it executes.
 *
 * <p><b>Lifetime.</b> The population lives in the root {@link ExtensionContext}'s store, so it is
 * one per run, and is closed with it.
 */
public final class FactGraphs implements AutoCloseable {

    /** How many stores each catalog's graphs are spread over. */
    static final int SHARDS_PER_CATALOG = 4;

    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(FactGraphs.class);

    private static final AtomicLong BOOTS = new AtomicLong();

    /** Every scenario, by path; building blocks are pulled in rather than run. */
    private final Map<String, CorpusDocuments.Document> documents;

    private final CatalogInput fixture;
    private final List<ClasspathEntry> classpath;
    private final Path directory;
    private final Map<ShardKey, Shard> shards = new ConcurrentHashMap<>();

    private record ShardKey(Optional<String> catalog, int index) {}

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
    }

    /** The run's population, opened by the first class that asks for it. */
    static FactGraphs of(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE)
            .computeIfAbsent(FactGraphs.class, key -> new FactGraphs(), FactGraphs.class);
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
            Math.floorMod(path.hashCode(), SHARDS_PER_CATALOG));
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
        shards.values().forEach(Shard::close);
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
        private final Map<String, CapturedSchema> graphs = new LinkedHashMap<>();
        private final Map<String, Verification> verifications = new LinkedHashMap<>();
        private final Map<String, Integer> captures = new LinkedHashMap<>();

        synchronized CapturedSchema ensure(String path, CorpusDocuments.Document document) {
            var schema = graphs.get(path);
            if (schema != null) {
                return schema;
            }
            if (store == null) {
                store = FactStores.inMemory(StoreUse.LONG_LIVED);
                BOOTS.incrementAndGet();
            }
            // The scenario run: its renders captured into this store as a build reads a folder, under
            // the configuration its own file states, and each claim checked as it arrives.
            var scenario = Scenario.load(path);
            var keyed = FactDocuments.catalogPackage(document, fixture);
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
                    built[0] = CapturedStore.captureFolder(dsl, folder, path, catalog, classpath,
                        FactDocuments.StatedConfiguration.of(texts), at, !first);
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

        synchronized void close() {
            if (store != null) {
                store.close();
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

        /** Captures every graph in {@code paths} not yet captured, in parallel across stores. */
        public void warm(Collection<String> paths) {
            population().warm(paths);
        }

        /** How many times {@code path}'s graph has been captured in this run: none, then one. */
        public int captures(String path) {
            return population().captures(path);
        }

        /** Every plain document's path, in folder then name order. */
        Set<String> paths() {
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
        <T> List<T> eachStore(BiFunction<DSLContext, Set<String>, T> body) {
            var results = new ArrayList<T>();
            for (var shard : population().shards.values()) {
                var graphs = shard.graphs();
                if (!graphs.isEmpty()) {
                    results.add(answer(readerOf(shard).read(dsl -> body.apply(dsl, graphs))));
                }
            }
            return results;
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

        private static <T> T answer(StoreAnswer<T> answer) {
            return switch (answer) {
                case StoreAnswer.Answered<T> answered -> answered.value();
                case StoreAnswer.OutOfBudget<T> out -> throw new IllegalStateException(
                    "an unbounded fact-graph reader ran out of budget on " + out.sql());
            };
        }
    }
}
