package no.sikt.graphitron.model.capture.code;

import no.sikt.graphitron.model.classpath.ClassfileCensus;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.read.SourceStamp;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Rows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The gatherer that reads the classpath, once, and owns the store's record of what was read.
 *
 * <p>{@link CodeCapture} and {@link JvmCapture} both write from the classpath and are handed this
 * reading rather than opening every entry for themselves. They own no provenance either: the
 * source row, its stamp and this graph's claim are written here, so where a row came from has one
 * answer.
 *
 * <p>The stamp is on the claim as well as the source, because {@code store_source} is store-global
 * and cannot say whether <em>this</em> graph holds rows derived from those bytes. Two graphs on
 * one jar get the wrong answer from the global column and the right one from the claim.
 *
 * <p>An entry gone from disk is forgotten and its rows cascade away with the source row. An entry
 * this graph merely stopped reading is unclaimed, not forgotten; the claim's foreign key refuses
 * the delete while another graph holds one.
 */
public final class ClasspathSourceCapture {

    private ClasspathSourceCapture() {}

    /**
     * One entry of the classpath and what this reading found in it.
     *
     * <p>Fewer arms than a document has, because an entry states less. There is no parse to refuse:
     * {@link ClassfileCensus} declares nothing for an entry it cannot open rather than failing the
     * reading, so an unreadable entry and an empty one are one case here. And nothing needs an arm
     * for an entry that left, the source row's deletion being what removes its rows.
     */
    public sealed interface ClasspathSource {

        /** The entry, spelled as the store names it. */
        String sourceName();

        /**
         * An entry this reading opened, and the classes it declared.
         *
         * <p>Carries its classes because every consumer of this family is set-based over the whole
         * classpath: an ancestry walk resolves a supertype in whichever entry declares it, so an
         * entry withheld from the reading would not make its classes unwritten, it would make
         * another entry's answers wrong.
         */
        record Read(String sourceName, List<ClassfileCensus.ClassAt> classes)
            implements ClasspathSource {}

        /**
         * An entry the reading skipped before opening it.
         *
         * <p>A transitive dependency is the case: it is on the classpath and is not this run's to
         * read. It is named here rather than left out so a caller can tell an entry nobody read
         * from an entry that declared nothing, which the store deliberately cannot, no row being
         * written for it at all.
         */
        record Skipped(String sourceName) implements ClasspathSource {}

        /**
         * An entry whose bytes are the ones this graph's rows were already derived from.
         *
         * <p>Not opened, which is the whole of the saving: the classfiles are the expensive thing
         * and nothing in them has moved. Its rows carry the instant of the reading that wrote
         * them and are outside every sweep this reading performs, a sweep being scoped to the
         * entries it read.
         *
         * <p>Per graph, not per entry: the same jar read by two graphs is unchanged for whichever
         * of them last transcribed these bytes and changed for the other.
         */
        record Unchanged(String sourceName) implements ClasspathSource {}
    }

    /**
     * Reads {@code classpath} and records what was read.
     *
     * <p>The instant is the caller's, on every gatherer's terms: the rows the gatherers below write
     * carry it, and their sweeps tell this reading's rows from the last one's by it.
     */
    public static Reading capture(DSLContext dsl, String graph, List<ClasspathEntry> classpath,
                                  String skipPrefix, LocalDateTime readAt) {
        var declared = declared(classpath);
        if (declared.isEmpty()) {
            // A run handed no classpath has nothing to say about one. Claiming nothing and
            // forgetting nothing is the whole of that: a reading that treated an empty classpath
            // as "every entry has left" would reclaim another reading's corpus.
            return new Reading(new ClassfileCensus.Census(List.of(), List.of()), List.of());
        }
        // What this graph's rows of each entry were read from, before anything is written over it.
        var held = heldStamps(dsl, graph);
        var changed = new java.util.ArrayList<ClasspathEntry>();
        var unchanged = new java.util.ArrayList<String>();
        for (ClasspathEntry entry : declared) {
            String name = entry.path().toString();
            String stamp = stampOf(name);
            if (stamp != null && stamp.equals(held.get(name))) {
                unchanged.add(name);
            } else {
                changed.add(entry);
            }
        }
        var reading = read(dsl, changed, skipPrefix, readAt);
        var claimed = new LinkedHashSet<String>();
        declared.forEach(entry -> claimed.add(entry.path().toString()));

        var sources = new java.util.ArrayList<>(reading.sources());
        unchanged.forEach(name -> sources.add(new ClasspathSource.Unchanged(name)));
        for (ClasspathEntry entry : classpath) {
            String name = entry.path().toString();
            if (!claimed.contains(name)) {
                sources.add(new ClasspathSource.Skipped(name));
            }
        }

        release(dsl, graph, List.copyOf(claimed));
        claim(dsl, graph, reading.census().entries(), readAt);
        // After the claims this graph has let go, and never before them: the claim is what refuses
        // the delete, so an entry still claimed here is one this reading would fail on rather than
        // one it would keep. Every entry the configuration named counts as read, an entry left
        // alone being one this reading vouched for rather than one it lost.
        reclaim(dsl, claimed);
        return new Reading(reading.census(), List.copyOf(sources));
    }

    /** The entries this run may read at all, which is every one it did not inherit. */
    private static List<ClasspathEntry> declared(List<ClasspathEntry> classpath) {
        return classpath.stream()
            .filter(entry -> entry.origin() != ClasspathEntry.Origin.TRANSITIVE)
            .toList();
    }

    /**
     * What each of this graph's classpath entries was last transcribed from, by entry.
     *
     * <p>The graph's own answer and not the store's. Two graphs may read one jar, and the stamp on
     * {@code store_source} says what it hashed to for whichever of them read it last, which is not
     * a statement about whether this graph holds rows derived from those bytes.
     */
    private static java.util.Map<String, String> heldStamps(DSLContext dsl, String graph) {
        var m = STORE_GRAPH_SOURCE;
        var t = STORE_SOURCE;
        return dsl.select(m.SOURCE_NAME, m.STAMP)
            .from(m).join(t).on(t.SOURCE_NAME.eq(m.SOURCE_NAME))
            .where(m.GRAPH_NAME.eq(graph))
            .and(t.SOURCE_KIND.in("JAR", "DIRECTORY"))
            .fetchMap(m.SOURCE_NAME, m.STAMP);
    }

    /**
     * The claims this graph has stopped holding, which are the entries it no longer reads.
     *
     * <p>Only the claim goes. The entry's rows are store-global and another graph may be reading
     * them, so what a graph stops reading is unclaimed rather than forgotten; forgetting is the
     * filesystem's to decide and {@link #reclaim} below asks it.
     */
    private static void release(DSLContext dsl, String graph, List<String> read) {
        var m = STORE_GRAPH_SOURCE;
        var t = STORE_SOURCE;
        Condition scope = m.GRAPH_NAME.eq(graph)
            .and(m.SOURCE_NAME.in(dsl.select(t.SOURCE_NAME).from(t)
                .where(t.SOURCE_KIND.in("JAR", "DIRECTORY"))));
        if (!read.isEmpty()) {
            scope = scope.and(m.SOURCE_NAME.notIn(read));
        }
        dsl.deleteFrom(m).where(scope).execute();
    }

    /**
     * The reading alone, for a caller with no graph to claim against.
     *
     * <p>The classpath families are store-global: what a classfile declares is a fact about the
     * entry and not about whoever read it, so the rows and the source row beneath them are
     * complete without a claim. What the claim adds is the answer to whether a given graph may
     * leave an entry alone on a later reading, which a caller holding no graph is not asking.
     */
    public static Reading read(DSLContext dsl, List<ClasspathEntry> classpath,
                               String skipPrefix, LocalDateTime readAt) {
        var census = ClassfileCensus.read(declared(classpath), skipPrefix);
        var read = new LinkedHashSet<String>();
        census.entries().forEach(at -> read.add(at.source()));
        writeSources(dsl, census.entries(), readAt);

        var byEntry = census.classes().stream()
            .collect(java.util.stream.Collectors.groupingBy(ClassfileCensus.ClassAt::source));
        var sources = new java.util.ArrayList<ClasspathSource>();
        for (var at : census.entries()) {
            sources.add(new ClasspathSource.Read(at.source(),
                byEntry.getOrDefault(at.source(), List.of())));
        }
        for (ClasspathEntry entry : classpath) {
            String name = entry.path().toString();
            if (!read.contains(name)) {
                sources.add(new ClasspathSource.Skipped(name));
            }
        }

        return new Reading(census, List.copyOf(sources));
    }

    /**
     * What the reading produced: the census for the gatherers that walk the whole classpath, and
     * the entries for the ones that need to know which they may write against.
     */
    public record Reading(ClassfileCensus.Census census, List<ClasspathSource> sources) {

        /** The entries this reading opened, which is the scope every gatherer's sweep takes. */
        public List<String> read() {
            return sources.stream()
                .filter(ClasspathSource.Read.class::isInstance)
                .map(ClasspathSource::sourceName)
                .toList();
        }
    }

    /**
     * The entries of a reading the caller performed, for a fixture that states a census and has no
     * classpath behind it.
     *
     * <p>Here rather than in the gatherer that writes from such a census, because provenance has
     * one owner however the reading was come by: a gatherer that recorded its own source rows on
     * this path and not the other would be the second answer this class exists to remove. What a
     * stated census cannot say, this cannot record, so the rows carry the kind read off the
     * filesystem and no origin or coordinate at all.
     *
     * @deprecated provenance for a stated census; it goes with the fixtures that state one.
     */
    @Deprecated(forRemoval = true)
    public static void stated(DSLContext dsl, String graph, List<String> sourceNames,
                              LocalDateTime readAt) {
        var named = new LinkedHashSet<String>();
        sourceNames.forEach(name -> named.add(name == null ? "" : name));
        if (named.isEmpty()) {
            return;
        }
        var t = STORE_SOURCE;
        var rows = named.stream().collect(Rows.toRowList(
            name -> val(name, t.SOURCE_NAME),
            name -> val(kindOf(name), t.SOURCE_KIND),
            name -> val(stampOf(name), t.STAMP),
            name -> val(modifiedAt(name), t.MTIME),
            name -> val(readAt, t.LAST_SEEN),
            name -> val(readAt, t.READ_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.SOURCE_KIND, t.STAMP, t.MTIME, t.LAST_SEEN,
                    t.READ_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.STAMP, excluded(t.STAMP))
                .set(t.MTIME, excluded(t.MTIME))
                .set(t.LAST_SEEN, excluded(t.LAST_SEEN))
                .set(t.READ_AT, excluded(t.READ_AT)));
        var m = STORE_GRAPH_SOURCE;
        var claims = named.stream().collect(Rows.toRowList(
            name -> val(graph, m.GRAPH_NAME),
            name -> val(name, m.SOURCE_NAME),
            name -> val(stampOf(name), m.STAMP),
            name -> val(readAt, m.READ_AT)));
        BindBatch.execute(dsl, claims, markers ->
            dsl.insertInto(m, m.GRAPH_NAME, m.SOURCE_NAME, m.STAMP, m.READ_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(m.STAMP, excluded(m.STAMP))
                .set(m.READ_AT, excluded(m.READ_AT)));
    }

    /** What the store calls an entry, read off the filesystem where a stated census says nothing. */
    private static String kindOf(String sourceName) {
        return !sourceName.isEmpty() && Files.isDirectory(Path.of(sourceName))
            ? "DIRECTORY" : "JAR";
    }

    /** The registry row every classpath row hangs its source on, one per entry read. */
    private static void writeSources(DSLContext dsl, List<ClassfileCensus.EntryAt> entries,
                                     LocalDateTime readAt) {
        if (entries.isEmpty()) {
            return;
        }
        var t = STORE_SOURCE;
        var rows = entries.stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.kind(), t.SOURCE_KIND),
            at -> val(at.origin(), t.ORIGIN),
            at -> val(at.coordinate(), t.COORDINATE),
            at -> val(stampOf(at.source()), t.STAMP),
            at -> val(modifiedAt(at.source()), t.MTIME),
            at -> val(readAt, t.LAST_SEEN),
            at -> val(readAt, t.READ_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.SOURCE_KIND, t.ORIGIN, t.COORDINATE, t.STAMP,
                    t.MTIME, t.LAST_SEEN, t.READ_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.ORIGIN, excluded(t.ORIGIN))
                .set(t.COORDINATE, excluded(t.COORDINATE))
                .set(t.STAMP, excluded(t.STAMP))
                .set(t.MTIME, excluded(t.MTIME))
                .set(t.LAST_SEEN, excluded(t.LAST_SEEN))
                .set(t.READ_AT, excluded(t.READ_AT)));
    }

    /**
     * This graph's claim on each entry, carrying what it was read from and when.
     *
     * <p>The pair is what lets a later reading leave an entry alone: the stamp says what the bytes
     * were and the instant says when this graph last transcribed them, and a stamp that could not
     * date itself would license a skip against an answer of unknown age.
     */
    private static void claim(DSLContext dsl, String graph, List<ClassfileCensus.EntryAt> entries,
                              LocalDateTime readAt) {
        if (entries.isEmpty()) {
            return;
        }
        var m = STORE_GRAPH_SOURCE;
        var rows = entries.stream().collect(Rows.toRowList(
            at -> val(graph, m.GRAPH_NAME),
            at -> val(at.source(), m.SOURCE_NAME),
            at -> val(stampOf(at.source()), m.STAMP),
            at -> val(readAt, m.READ_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(m, m.GRAPH_NAME, m.SOURCE_NAME, m.STAMP, m.READ_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(m.STAMP, excluded(m.STAMP))
                .set(m.READ_AT, excluded(m.READ_AT)));
    }

    /**
     * The entries whose files are gone.
     *
     * <p>Not the entries this reading did not read: the relation is shared and ungraphed, so
     * absence from one run's classpath says nothing, and only absence from the filesystem says an
     * entry has stopped being one. A source another graph still claims is refused by that claim's
     * own foreign key rather than tested for here, and the rows read from a source this does
     * delete go with it, the classpath families cascading from it.
     */
    private static void reclaim(DSLContext dsl, LinkedHashSet<String> read) {
        var t = STORE_SOURCE;
        var vanished = dsl.select(t.SOURCE_NAME).from(t)
            .where(t.SOURCE_KIND.in("JAR", "DIRECTORY"))
            .fetch(t.SOURCE_NAME).stream()
            .filter(name -> !read.contains(name))
            .filter(name -> !Files.exists(Path.of(name)))
            .toList();
        if (!vanished.isEmpty()) {
            dsl.deleteFrom(t).where(t.SOURCE_NAME.in(vanished)).execute();
        }
    }

    /**
     * What the entry's bytes hash to, or null where it is a directory.
     *
     * <p>A directory is deliberately unstamped. It changes on every compile, so hashing it would
     * buy an invalidation that always fires and pay a full walk to decide that.
     */
    private static String stampOf(String sourceName) {
        Path path = Path.of(sourceName);
        return Files.isDirectory(path) ? null : SourceStamp.ofFile(path);
    }

    /** When the entry was last written, or null where it cannot be read. */
    private static LocalDateTime modifiedAt(String sourceName) {
        try {
            return LocalDateTime.ofInstant(
                Files.getLastModifiedTime(Path.of(sourceName)).toInstant(),
                ZoneId.systemDefault()).withNano(0);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
