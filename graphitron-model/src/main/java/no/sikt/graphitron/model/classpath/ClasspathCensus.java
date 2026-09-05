package no.sikt.graphitron.model.classpath;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.sources.ClasspathSources;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The class census, held across the rounds of one session and re-read per entry rather than whole.
 *
 * <p>{@link ClasspathScanner#scan} reads and parses every classfile on the compile classpath. A
 * one-shot goal pays that once, which is the right price; a {@code graphitron:dev} session pays it
 * on every round, including the rounds where a {@code .graphqls} save cannot have changed a single
 * byte of it. This class is what a long-lived session holds so that a round costs work
 * proportional to what the developer changed. Hand one instance to every round's generator and it
 * re-reads what moved; hand a fresh one to each round, which is what a one-shot goal does, and it
 * behaves exactly like the scanner it wraps.
 *
 * <p>The classpath is two populations with opposite characteristics, and they get different
 * detectors:
 *
 * <ul>
 *   <li><b>Jars</b> are most of the bytes and change only when a sibling project is installed from
 *       another checkout. A jar is verified by content hash, through
 *       {@link ClasspathSources#hash}, which is the same function the store's persisted stamps use.
 *       Hashing a jar set costs roughly an eighth of parsing it, so the whole population is
 *       verified for a fraction of one round's parse. Cheaper still where the producer already
 *       knows the answer: an entry carrying a {@link ClasspathEntry#suppliedStamp()} is verified
 *       against that instead, and most of a classpath is release artifacts whose identity the
 *       resolver established once and whose coordinate cannot be republished under new bytes.
 *   <li><b>Directories</b> are {@code target/classes}, a few bytes that the compiler rewrites on
 *       every save. A directory is verified per file by size and modification time, and only the
 *       files whose stamp moved are re-parsed. Hashing a directory would require reading the bytes
 *       the round is trying not to read, so a stat is the only detector here that saves anything,
 *       and per-file grain is what keeps the invalidation firing for the files the compiler
 *       rewrote instead of for the directory as a whole.
 * </ul>
 *
 * <p>Size and modification time are a heuristic: a file whose content changed while both stayed
 * identical reads as unchanged. {@code store_source}'s own rationale calls the triple "tolerable
 * while a wrong answer dies with the JVM and not tolerable once it survives a build", and this
 * census is the first case, holding nothing past the session. The compiler does not produce the
 * failure either, because writing a classfile moves its modification time, and the operations that
 * replace class output underneath a session ({@code mvn clean}, a rebuild after a branch switch)
 * move it too. Persisted classpath facts keep the content hash for the reason the rationale gives.
 *
 * <p>What makes the reuse safe is that a reference is data. {@link CompletionData.ExternalReference}
 * is strings, booleans and lists with no {@link Class} or loader in it, so a census outliving the
 * codegen loader that its classes were read beside carries no class-identity hazard. The jOOQ
 * catalog is the opposite case and is deliberately not held here: it resolves through the codegen
 * loader that {@code withCodegenScope} closes at the end of every round.
 *
 * <p>Reads are serialised. A session's cadences can fire from more than one thread, and two rounds
 * folding into one cache would interleave a rebuild with a read.
 */
public final class ClasspathCensus {

    /** One entry's cached read, keyed by the entry path. Rebuilt for an entry whose bytes moved. */
    private sealed interface Cached {}

    /** A jar, verified whole: its content hash and the references parsed at that hash. */
    private record CachedJar(String hash, List<CompletionData.ExternalReference> references)
        implements Cached {}

    /**
     * A directory, verified per file. The map is in walk order, because the census keeps the first
     * occurrence of a duplicated FQN and that has to be the same occurrence a cold scan keeps, and
     * it holds only files the last walk saw; see {@link #readDirectory} for why it is rebuilt.
     */
    private record CachedDirectory(LinkedHashMap<Path, CachedFile> files) implements Cached {}

    /** One classfile's stamp and what parsing it produced, which is one reference or none. */
    private record CachedFile(long size, long modifiedMillis,
                              List<CompletionData.ExternalReference> references) {}

    private final Map<Path, Cached> cached = new HashMap<>();

    /**
     * Where each round's cost is said, or null for a caller that does not want it said. Registered
     * rather than returned so that every read reports on every cadence: a caller that had to ask
     * after the fact would have to ask at each of its call sites, and the site that forgot would go
     * quiet on exactly the rounds that re-read something.
     */
    private Consumer<Round> sink;

    /**
     * The jOOQ package the cache was built against. It decides which classes the parse admits, so a
     * change to it invalidates everything rather than one entry.
     */
    private String cachedJooqPackage;

    /**
     * The census over {@code entries}, reusing every entry whose bytes have not moved since the
     * last call. {@code TRANSITIVE} entries are skipped before they are opened, as in
     * {@link ClasspathScanner#scan}, and entries that have left the classpath are dropped from the
     * cache.
     *
     * @return the census and what this round paid for it
     */
    public synchronized Reading read(List<ClasspathEntry> entries, String jooqPackage) {
        long startedAt = System.nanoTime();
        if (!jooqPackage.equals(cachedJooqPackage)) {
            cached.clear();
            cachedJooqPackage = jooqPackage;
        }

        var counters = new Counters();
        var live = new ArrayList<Path>();
        var stamps = new LinkedHashMap<String, String>();
        var perEntry = new ArrayList<List<CompletionData.ExternalReference>>();
        for (ClasspathEntry classified : entries) {
            if (classified.origin() == ClasspathEntry.Origin.TRANSITIVE) {
                continue;
            }
            live.add(classified.path());
            perEntry.add(readEntry(classified, jooqPackage, counters, stamps));
        }
        cached.keySet().retainAll(live);

        var census = ClasspathScanner.compose(perEntry);
        var round = new Round(
            counters.jarsReused, counters.jarsRead,
            counters.filesReused, counters.filesRead,
            census.size(), (System.nanoTime() - startedAt) / 1_000_000L);
        if (sink != null) {
            sink.accept(round);
        }
        return new Reading(census, round, stamps);
    }

    /**
     * Says every round's cost to {@code sink}, replacing any previous one. The session that owns
     * this census registers once and every read reports, including the reads inside a cadence the
     * owner does not otherwise log. A census with no sink says nothing, which is what a one-shot
     * goal wants: it has one round and no round to compare it against.
     */
    public synchronized void reportTo(Consumer<Round> sink) {
        this.sink = sink;
    }

    private List<CompletionData.ExternalReference> readEntry(
            ClasspathEntry classified, String jooqPackage, Counters counters,
            Map<String, String> stamps) {
        Path path = classified.path();
        if (Files.isDirectory(path)) {
            return readDirectory(path, jooqPackage, counters);
        }
        if (ClasspathScanner.isJar(path)) {
            return readJar(classified, jooqPackage, counters, stamps);
        }
        // Neither a directory nor a readable jar: the pre-compile state, which the scanner also
        // passes over. Nothing is cached for it, so it costs one existence check per round and
        // starts being read the moment it appears.
        cached.remove(path);
        return List.of();
    }

    private List<CompletionData.ExternalReference> readJar(
            ClasspathEntry classified, String jooqPackage, Counters counters,
            Map<String, String> stamps) {
        Path jar = classified.path();
        // The producer's identity for this entry where it has one, and the bytes otherwise. A
        // supplied stamp is what makes the whole population question answerable: most of a
        // classpath is release artifacts a repository resolved, and hashing those re-establishes
        // something already established. Whichever value this round used is what the entry is
        // verified against next round and what the capture is handed, so the two consumers can
        // never disagree about which bytes a partition describes.
        //
        // Null where the jar could not be read. That must not compare equal to a previous null, or
        // an unreadable jar would pin whatever it was cached with; it is re-read instead, which is
        // cheap because the scanner passes over a jar it cannot open.
        String hash = classified.suppliedStamp() != null
            ? classified.suppliedStamp()
            : ClasspathSources.hash(jar);
        if (hash != null) {
            stamps.put(jar.toString(), hash);
        }
        if (hash != null
            && cached.get(jar) instanceof CachedJar previous
            && hash.equals(previous.hash())) {
            counters.jarsReused++;
            return previous.references();
        }
        counters.jarsRead++;
        var references = ClasspathScanner.scanEntry(jar, jooqPackage);
        if (hash == null) {
            cached.remove(jar);
        } else {
            cached.put(jar, new CachedJar(hash, references));
        }
        return references;
    }

    private List<CompletionData.ExternalReference> readDirectory(
            Path root, String jooqPackage, Counters counters) {
        var previous = cached.get(root) instanceof CachedDirectory dir
            ? dir.files()
            : Map.<Path, CachedFile>of();
        var current = new LinkedHashMap<Path, CachedFile>();
        var references = new ArrayList<CompletionData.ExternalReference>();
        String source = root.toString();

        // Files.walk, and not walkFileTree with the attributes the visitor is handed, so the order
        // is the order a cold scan produces. The extra stat per file is what that costs, and a
        // stat-only walk is a small fraction of reading the same files.
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(file -> file.getFileName().toString().endsWith(".class"))
                .forEach(file -> {
                    long size;
                    long modified;
                    try {
                        size = Files.size(file);
                        modified = Files.getLastModifiedTime(file).toMillis();
                    } catch (IOException e) {
                        // Vanished between the walk and the stat, which a compile running beside
                        // this walk produces. Leave it out of the census and out of the cache; the
                        // next round sees whatever replaced it.
                        return;
                    }
                    CachedFile held = previous.get(file);
                    CachedFile fresh;
                    if (held != null && held.size() == size && held.modifiedMillis() == modified) {
                        counters.filesReused++;
                        fresh = held;
                    } else {
                        counters.filesRead++;
                        fresh = new CachedFile(size, modified,
                            ClasspathScanner.readClassFile(file, jooqPackage, source)
                                .map(List::of)
                                .orElseGet(List::of));
                    }
                    current.put(file, fresh);
                    references.addAll(fresh.references());
                });
        } catch (IOException e) {
            throw new UncheckedIOException("classpath scan failed at " + root, e);
        }

        // Rebuilt from this walk rather than merged into the old map, so the entry holds what the
        // directory holds. The census is already right either way, being accumulated from the walk
        // above rather than read back out of here; what a merge would cost is a cache that grows
        // with every class the session ever compiled, and a deleted-then-recreated file reusing a
        // parse from before it went away.
        cached.put(root, new CachedDirectory(current));
        return List.copyOf(references);
    }

    /**
     * The census, what the round paid for it, and the identity this round verified each jar
     * against, keyed by {@link Path#toString()} to match {@code store_source.source_name}.
     *
     * <p>The stamps ride here because the capture needs exactly them and cannot recompute them
     * safely. The retention decision compares a jar against what the store recorded for it, and
     * the answer has to describe the bytes this round parsed: the store outlives the build, so a
     * partition retained against a later read keeps rows nothing will recompute. Directories carry
     * no entry, being verified per file rather than whole, which is the same population the
     * retention decision already skips.
     */
    public record Reading(List<CompletionData.ExternalReference> references, Round round,
                          Map<String, String> stamps) {
        public Reading {
            references = List.copyOf(references);
            stamps = Map.copyOf(stamps);
        }
    }

    /**
     * What one round of {@link #read} did, by population. The count that matters is what was
     * <em>not</em> re-read: a round that reuses everything is the loop working, and a round that
     * re-reads a population nothing touched is the invalidation regressing. Nothing here fails a
     * build, so without the report a regression is invisible, the loop still producing correct
     * output and only slowly.
     */
    public record Round(int jarsReused, int jarsRead, int filesReused, int filesRead,
                        int classes, long millis) {

        /** Whether this round re-read anything at all, which a cold round always did. */
        public boolean reusedEverything() {
            return jarsRead == 0 && filesRead == 0;
        }

        /** The sentence a caller logs. The caller logs; this package reports. */
        public String report() {
            var line = new StringBuilder("classpath census: ").append(classes).append(" classes in ")
                .append(millis).append(" ms, ");
            if (reusedEverything()) {
                line.append("nothing re-read");
            } else {
                line.append(jarsRead).append(" of ").append(jarsRead + jarsReused)
                    .append(" jars re-read, ")
                    .append(filesRead).append(" of ").append(filesRead + filesReused)
                    .append(" class files re-parsed");
            }
            return line.toString();
        }
    }

    /** Mutable tallies for one round, so the counting does not thread through every return. */
    private static final class Counters {
        private int jarsReused;
        private int jarsRead;
        private int filesReused;
        private int filesRead;
    }
}
