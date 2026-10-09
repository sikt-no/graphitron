package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import org.jooq.DSLContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The classification corpus captured once per test JVM, for the sweeps that only read it.
 *
 * <p>Every {@link CorpusDocuments} document is captured as its own graph, named by
 * {@link CorpusDocuments.Document#id()}, from {@link CorpusDocuments#prelude()} followed by the
 * document, against the generated jOOQ catalog. Several classes sweep that same population and
 * each used to capture it for itself, sixty captures and a derivation stratum apiece, which made a
 * handful of cases the most expensive part of a module's run. One capture serves them all now.
 *
 * <p><b>Populations, keyed.</b> {@link #over(Path)} names a population by the class root its first
 * graph reads, which is the one input the sweeps disagree on: a sweep whose relations the code
 * family feeds reads the classpath as well as the census scanned from it. Each key is captured the
 * first time any class asks for it and never again. {@link #initializations()} counts captures and
 * {@link #populations()} counts keys, and a module holds them equal.
 *
 * <p><b>Readers only.</b> Classes run concurrently and the capturing connection is single-threaded,
 * so neither the {@link CapturedStore} nor its context is handed out. {@link #reader()} mints a
 * {@link StoreReader}, a connection of its own onto the same in-memory database, and {@link #read}
 * wraps one for a caller that wants an answer rather than a reader. The capture, stratum included,
 * completes under this object's lock before the first reader is minted, so a reader sees the whole
 * population.
 *
 * <p><b>Read-only, enforced.</b> A reader's transaction always rolls back, so a row written inside
 * {@link StoreReader#read} never lands. Two things get past a rollback: DDL, which H2 commits
 * implicitly, and a context kept past {@code read()}, which then runs on autocommit. Every handout
 * therefore compares the store's base tables and its per-table row census against what the capture
 * left, and fails naming whatever changed. The set of tables is compared as well as the counts,
 * because a census keyed by the tables the capture made sees a table dropped and not one created.
 * What is not caught is an {@code UPDATE} through a leaked context: equal counts do not prove equal
 * content. That residue needs a context deliberately kept past {@code read()}, which the reader's
 * own javadoc already forbids, and the rollback covers every ordinary use.
 *
 * <p>Never closed. An in-memory H2 dies with the JVM, and a class finishing has nobody to hand
 * the store to; {@code BundledVocabulary} in the language server's tests has the same lifetime for
 * the same reason.
 */
public final class CorpusStore {

    /** The shared populations, keyed by the class root their first graph reads, or none. */
    private static final Map<Key, CorpusStore> SHARED = new ConcurrentHashMap<>();

    private static final AtomicInteger INITIALIZATIONS = new AtomicInteger();

    private record Key(Path classRoot) {}

    /** What fills the store; run once, under the lock, on first use. */
    private final Supplier<CapturedStore> fill;

    /** Whether this population is one of the shared ones, which is all {@link #INITIALIZATIONS} counts. */
    private final boolean shared;

    private CapturedStore captured;
    private Set<String> tables;
    private String census;
    private Map<String, Integer> counts;

    private CorpusStore(Supplier<CapturedStore> fill, boolean shared) {
        this.fill = fill;
        this.shared = shared;
    }

    /** The corpus with no classpath: catalog and SDL facts only. */
    public static CorpusStore bare() {
        return SHARED.computeIfAbsent(new Key(null), key -> new CorpusStore(() -> capture(null), true));
    }

    /**
     * The corpus with {@code classRoot} read as the build's own output on every graph: the shape a
     * sweep over a relation the code family feeds wants, a condition hop routing off what
     * {@code @condition} may name.
     */
    public static CorpusStore over(Path classRoot) {
        Path root = classRoot.toAbsolutePath().normalize();
        return SHARED.computeIfAbsent(new Key(root), key -> new CorpusStore(() -> capture(root), true));
    }

    /**
     * A private store filled by {@code fill} and guarded the same way, for the tests of the guard
     * itself, which must write to a store nobody else reads.
     */
    static CorpusStore privately(Supplier<CapturedStore> fill) {
        return new CorpusStore(fill, false);
    }

    /** How many shared populations have been captured in this JVM. */
    public static int initializations() {
        return INITIALIZATIONS.get();
    }

    /** How many shared populations have been asked for in this JVM. */
    public static int populations() {
        return SHARED.size();
    }

    /**
     * A reader onto the population, minted after the check that nothing has written to it. The
     * caller closes it. Unbounded, as every fixture reader in the reactor is.
     *
     * @throws IllegalStateException if a table or a row count differs from what the capture left
     */
    public synchronized StoreReader reader() {
        if (captured == null) {
            initialize();
        } else {
            verifyUnwritten();
        }
        return captured.reader();
    }

    /** Runs {@code query} in one read transaction over the population and returns what it produced. */
    public <T> T read(Function<DSLContext, T> query) {
        try (StoreReader reader = reader()) {
            return switch (reader.read(query)) {
                case StoreAnswer.Answered<T> answered -> answered.value();
                case StoreAnswer.OutOfBudget<T> out -> throw new IllegalStateException(
                    "an unbounded corpus reader ran out of budget on " + out.sql());
            };
        }
    }

    /** {@link #read} for a body that asserts rather than answers. */
    public void run(Consumer<DSLContext> body) {
        read(dsl -> {
            body.accept(dsl);
            return null;
        });
    }

    /** Closes a {@link #privately} filled store. The shared ones are never closed. */
    void close() {
        if (shared) {
            throw new IllegalStateException("a shared corpus store lives as long as the JVM");
        }
        if (captured != null) {
            captured.close();
        }
    }

    private void initialize() {
        captured = fill.get();
        if (shared) {
            INITIALIZATIONS.incrementAndGet();
        }
        DSLContext dsl = captured.dsl();
        tables = new LinkedHashSet<>(ThreadConfinedStore.baseTables(dsl));
        census = ThreadConfinedStore.census(List.copyOf(tables));
        counts = ThreadConfinedStore.counts(dsl, census);
    }

    private void verifyUnwritten() {
        DSLContext dsl = captured.dsl();
        var now = new LinkedHashSet<>(ThreadConfinedStore.baseTables(dsl));
        if (!now.equals(tables)) {
            var created = new ArrayList<>(now);
            created.removeAll(tables);
            var dropped = new ArrayList<>(tables);
            dropped.removeAll(now);
            throw new IllegalStateException(("the shared corpus store's tables changed after its"
                + " capture: created %s, dropped %s. A reader ran DDL, which H2 commits past the"
                + " reader's rollback, and every class reading this store after it reads a different"
                + " schema. A case that needs DDL wants a store of its own.").formatted(created, dropped));
        }
        var found = ThreadConfinedStore.counts(dsl, census);
        var changed = counts.entrySet().stream()
            .filter(entry -> !entry.getValue().equals(found.get(entry.getKey())))
            .map(entry -> "%s (%d rows, captured with %d)".formatted(
                entry.getKey(), found.get(entry.getKey()), entry.getValue()))
            .toList();
        if (!changed.isEmpty()) {
            throw new IllegalStateException(("the shared corpus store's rows changed after its"
                + " capture: %s. A reader's transaction rolls back, so the write came through a"
                + " DSLContext kept past StoreReader.read, which runs on autocommit. Keep every"
                + " query inside the read that handed the context over.").formatted(changed));
        }
    }

    private static CapturedStore capture(Path classRoot) {
        var ctx = TestRunContext.of();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        List<ClasspathEntry> classpath = classRoot == null ? List.of()
            : List.of(new ClasspathEntry(classRoot, ClasspathEntry.Origin.PROJECT, null, null));
        Path directory = directory();
        var documents = CorpusDocuments.documents();
        var first = documents.getFirst();
        CapturedStore store = classRoot == null
            ? CapturedStore.ownStoreOfCatalog(directory, first.id(), sdl(first), jooq)
            : CapturedStore.ownStoreOfCatalog(directory, first.id(), sdl(first), jooq,
                classRoot);
        for (var document : documents.subList(1, documents.size())) {
            store.andCatalogGraphWith(document.id(), sdl(document), jooq, classpath);
        }
        return store;
    }

    /** One document as every sweep reads it: the prelude, then the document. */
    public static String sdl(CorpusDocuments.Document document) {
        return CorpusDocuments.prelude() + "\n" + document.sdl();
    }

    /** Where the captured documents are written, removed as the JVM exits. */
    private static Path directory() {
        try {
            Path directory = Files.createTempDirectory("graphitron-corpus-store");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(directory)));
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            // A temporary directory left behind costs nothing a JVM exit should fail over.
        }
    }
}
