package no.sikt.graphitron.model.capture.code;

import no.sikt.graphitron.model.sink.Progress;
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
import java.util.stream.Stream;
import java.util.Collection;
import java.util.List;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_CLASS_FILE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The gatherer that reads the classpath, once, and owns the store's record of what was read.
 *
 * <p>{@link CodeCapture} writes from the classpath and is handed this reading rather than opening
 * every entry for itself. It owns no provenance either: the source row, its stamp and this graph's
 * claim are written here, so where a row came from has one answer. Two gatherers were handed this
 * reading while the census stood beside the arms, and the arrangement outlived the second one:
 * what a corpus reader owns is not a function of how many gatherers read it.
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
        Progress.started(dsl, graph, GATHERER);
        var read = dsl.transactionResult(
            tx -> gather(tx.dsl(), graph, classpath, skipPrefix, readAt));
        Progress.completed(dsl, graph, GATHERER);
        return read;
    }

    /** This gatherer's row in {@code store_graph_progress}. */
    private static final String GATHERER = "classpath-source";

    private static Reading gather(DSLContext dsl, String graph, List<ClasspathEntry> classpath,
                                  String skipPrefix, LocalDateTime readAt) {
        var declared = declared(classpath);
        if (declared.isEmpty()) {
            // A run handed no classpath has nothing to say about one. Claiming nothing and
            // forgetting nothing is the whole of that: a reading that treated an empty classpath
            // as "every entry has left" would reclaim another reading's corpus.
            return new Reading(new ClassfileCensus.Census(List.of(), List.of()), List.of(), graph);
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
        // Every entry this graph reads, which is the ones opened and the ones left alone alike. An
        // entry left alone is still this graph's to see: the claim is how a graph-scoped view
        // reaches a source-keyed fact, and a reading that declined to open a file did not stop
        // reading it. Read entries and unchanged ones only, never the whole declared list: an entry
        // nothing has ever opened has no source row for the claim to hang on.
        var read = new LinkedHashSet<String>();
        reading.census().entries().forEach(at -> read.add(at.source()));
        read.addAll(unchanged);
        claim(dsl, graph, List.copyOf(read), readAt);
        // After the claims this graph has let go, and never before them: the claim is what refuses
        // the delete, so an entry still claimed here is one this reading would fail on rather than
        // one it would keep. Every entry the configuration named counts as read, an entry left
        // alone being one this reading vouched for rather than one it lost.
        reclaim(dsl, claimed);
        return new Reading(reading.census(), List.copyOf(sources), graph);
    }

    /** The entries this run may read at all, which is every one it did not inherit. */
    private static List<ClasspathEntry> declared(List<ClasspathEntry> classpath) {
        return classpath.stream()
            .filter(entry -> entry.origin() != ClasspathEntry.Origin.TRANSITIVE)
            .toList();
    }

    /**
     * What each classpath entry the store holds rows for was last read from, by entry.
     *
     * <p>The store's answer and not the graph's, which is a correction. The question a skip asks is
     * whether the rows a reading would write are already there and already derived from these bytes,
     * and for this corpus that is not a per-graph question: no relation the classpath feeds carries
     * a graph at all, so the rows are the store's and a second graph reading one jar would write the
     * rows the first graph wrote. Asking the graph's own claim instead made every graph after the
     * first re-read a classpath nothing had changed, which on this module's output is sixteen
     * thousand rows re-derived per graph.
     *
     * <p>What stays per graph is the claim, not the reading. A graph must still say it reads an
     * entry, because that membership is how a graph-scoped view reaches a source-keyed fact; the
     * skip above leaves that write alone and declines only the reading behind it.
     */
    private static java.util.Map<String, String> heldStamps(DSLContext dsl, String graph) {
        var t = STORE_SOURCE;
        return dsl.select(t.SOURCE_NAME, t.STAMP)
            .from(t)
            .where(t.SOURCE_KIND.in("JAR", "DIRECTORY"))
            .and(t.STAMP.isNotNull())
            .fetchMap(t.SOURCE_NAME, t.STAMP);
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
        // The files under those entries, which is the grain the families below key themselves by
        // and therefore the grain a deletion has to be noticed at. Marked here and swept below, so
        // a class a compile removed takes its rows with it rather than outliving them.
        writeClassFiles(dsl, census.classes(), readAt);
        sweepClassFiles(dsl, read, readAt);

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

        return new Reading(census, List.copyOf(sources), null);
    }

    /**
     * What the reading produced: the census for the gatherers that walk the whole classpath, and
     * the entries for the ones that need to know which they may write against. The graph is the
     * one the reading was claimed for, which what is written from it records its progress under;
     * {@code null} from {@link #read}, which claims for none.
     */
    public record Reading(ClassfileCensus.Census census, List<ClasspathSource> sources,
                          String graph) {

        /** The entries this reading opened, which is the scope every gatherer's sweep takes. */
        public List<String> read() {
            return sources.stream()
                .filter(ClasspathSource.Read.class::isInstance)
                .map(ClasspathSource::sourceName)
                .toList();
        }
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
    private static void claim(DSLContext dsl, String graph, List<String> entries,
                              LocalDateTime readAt) {
        if (entries.isEmpty()) {
            return;
        }
        var m = STORE_GRAPH_SOURCE;
        var rows = entries.stream().collect(Rows.toRowList(
            source -> val(graph, m.GRAPH_NAME),
            source -> val(source, m.SOURCE_NAME),
            source -> val(stampOf(source), m.STAMP),
            source -> val(readAt, m.READ_AT)));
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
     * What the entry is, as something a later reading can compare: a jar by its bytes, a directory
     * by a walk of what it holds.
     *
     * <p>A directory used to be left unstamped, on the reasoning that it changes on every compile
     * and so would buy an invalidation that always fires while paying a full walk to decide it. The
     * first half is true and the second does not survive measurement. Over this module's own output,
     * 1904 files, the walk costs 66 to 82 milliseconds warm, against the roughly nine seconds the
     * read it can skip costs. That is under one percent to decide, which is worth paying on the
     * compile where it fires and worth a great deal on the readings where it does not: a store
     * capturing several graphs reads one classpath once rather than once each.
     *
     * <p>Size and modification time rather than content, and only for the directory. A jar is one
     * file, so hashing it is exact and costs one read; a directory is many, and reading all of them
     * to decide whether to read all of them answers the question by doing the work. What the stat
     * pair misses is a rewrite that keeps both, which for build output means a compiler writing
     * identical length at the same millisecond. The path is in the digest too, so a file added or
     * removed moves it even where every surviving file is untouched.
     */
    private static String stampOf(String sourceName) {
        Path path = Path.of(sourceName);
        return Files.isDirectory(path) ? directoryStamp(path) : SourceStamp.ofFile(path);
    }

    /**
     * The walk behind a directory's stamp: every file under it by path, length and modification
     * time, in an order the filesystem does not get to choose. Null where the walk fails, which
     * reads as changed, an entry nothing can look at having nothing to compare.
     */
    private static String directoryStamp(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            var lines = new java.util.ArrayList<String>();
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                lines.add(directory.relativize(file)
                    + "\u0001" + Files.size(file)
                    + "\u0001" + Files.getLastModifiedTime(file).toMillis());
            }
            java.util.Collections.sort(lines);
            return SourceStamp.of(String.join("\n", lines)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
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

    /**
     * One row per class <em>file</em> the reading found, which is not one row per class: a file may
     * declare more than one, and several classes naming one path are one file that was opened once.
     *
     * <p>What is written is what the reading saw of the file, its length and when it was last
     * written, so a later reading can compare without opening it. Which class came out of it is
     * code_class's to say.
     */
    private static void writeClassFiles(DSLContext dsl, List<ClassfileCensus.ClassAt> classes,
                                        LocalDateTime readAt) {
        record FileAt(String source, String path, long size, long mtime) {}
        var files = new java.util.LinkedHashMap<String, FileAt>();
        for (var at : classes) {
            files.putIfAbsent(at.source() + "\u0000" + at.filePath(),
                new FileAt(at.source(), at.filePath(), at.byteSize(), at.mtimeMillis()));
        }
        var t = STORE_CLASS_FILE;
        var rows = files.values().stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.path(), t.FILE_PATH),
            at -> val(at.size() < 0 ? null : at.size(), t.BYTE_SIZE),
            at -> val(at.mtime() < 0 ? null : LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(at.mtime()), java.time.ZoneId.systemDefault()), t.MTIME),
            at -> val(readAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.FILE_PATH, t.BYTE_SIZE, t.MTIME, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.BYTE_SIZE, excluded(t.BYTE_SIZE))
                .set(t.MTIME, excluded(t.MTIME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * The files this reading did not find under the entries it opened, which are the classes a
     * compile removed. Scoped to those entries: an entry nobody read this time says nothing about
     * its files, and sweeping it would delete a reading somebody else still holds.
     */
    private static void sweepClassFiles(DSLContext dsl, Collection<String> readEntries,
                                        LocalDateTime readAt) {
        if (readEntries.isEmpty()) {
            return;
        }
        var t = STORE_CLASS_FILE;
        dsl.deleteFrom(t)
            .where(t.SOURCE_NAME.in(readEntries))
            .and(t.TOUCHED_AT.ne(readAt))
            .execute();
    }
}
