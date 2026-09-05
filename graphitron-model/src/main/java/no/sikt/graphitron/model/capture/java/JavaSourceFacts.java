package no.sikt.graphitron.model.capture.java;

import no.sikt.graphitron.model.sources.ClasspathSources;
import no.sikt.graphitron.model.sources.Observation;
import no.sikt.graphitron.model.sources.SourceWalker;
import no.sikt.graphitron.model.tables.records.JavaClassDeclarationRecord;
import no.sikt.graphitron.model.tables.records.JavaFieldDeclarationRecord;
import no.sikt.graphitron.model.tables.records.JavaMethodDeclarationRecord;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.JAVA_CLASS_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_FIELD_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_FILE;
import static no.sikt.graphitron.model.Tables.JAVA_METHOD_DECLARATION;

/**
 * The {@code java_} family's writer: transcribes a source walk's declarations into the store, one
 * file at a time, on the {@code .java} cadence rather than a generator round's.
 *
 * <p>The unit of work is a file, because the fact's unit is a file. Each file whose content hash
 * differs from the stamp the store recorded is rewritten whole inside its own transaction, so no
 * reader ever sees half a file's declarations and an edit costs one parse and one small
 * transaction rather than a workspace rewrite. A file that still hashes to its stamp is skipped
 * entirely, which is what makes a cold dev session over a warm store cheap: the walker has to
 * re-read the sources to fill its own cache, but the store already agrees and nothing is written.
 *
 * <p>What the hash buys is worth stating in two halves, because the argument for it is right about
 * the persisted stamp and was wrong about this cadence. Right: a modification time is the heuristic
 * {@code store_source}'s stamp exists to avoid, a checkout, a rebase or a container layer all
 * defeating it, so a file this writer cannot otherwise vouch for is hashed and never believed on
 * its mtime. Wrong: this refresh used to hash every walked file on the argument that hashing is
 * cheap beside the parse it protects, and {@link SourceWalker} re-parses only the files whose
 * modification time moved, so on a warm save the hash is not cheap beside the parse, it is the
 * entire cost of the refresh.
 *
 * <p>An {@link Observation} is what closes that gap without weakening the stamp. Given one, the
 * refresh hashes the files the session cannot vouch for and skips the rest, and it dates every file
 * it did hash with the instant the pass began, whether it rewrote the row or found the stamp still
 * current: verification is the read, not the rewrite, and a file hashed and found equal to its
 * stamp has had its content read as surely as one that was rewritten. Without an observation every
 * file is hashed, which is what the one-shot goals get and what this class did before.
 *
 * <p>A walk owns the files under the roots it walked, and nothing else. Rows under those roots
 * that the walk did not see are the files that were deleted or renamed, and they are pruned; rows
 * under any other root belong to a walk this one knows nothing about, which in a store shared by
 * a workspace's modules is a sibling module's business. That is what {@code java_file.source_root}
 * is for.
 *
 * <p>Store trouble costs warmth, never the dev loop, on {@link no.sikt.graphitron.model.capture.compile.CompileFacts}'
 * terms: a write the store rejects logs and returns, having cost nothing but the rows it would
 * have written. Anything else escaping {@link #refresh} is a bug here rather than store trouble
 * and is deliberately not swallowed.
 *
 * <p>Two first-wins rules keep the writer honest about malformed input, since a parse reads what a
 * compiler would refuse: a file declaring one class name twice, or one class declaring one field
 * name twice, contributes the first of each. Overloads need no such rule, every declaration being
 * its own row under its own ordinal.
 */
public final class JavaSourceFacts {

    private static final Logger LOG = LoggerFactory.getLogger(JavaSourceFacts.class);

    /** The corpus this gatherer crawls, as {@code meta_gatherer_corpus} declares it. */
    private static final String CORPUS = "java-source";

    /** How many files one {@code IN} list carries when the pass dates what it verified. */
    private static final int DATE_CHUNK = 500;

    private final DSLContext dsl;
    private final Observation observation;

    /**
     * @param dsl the writer's store handle; in a dev session the session's own, shared with the
     *            readers that answer from it, so a refresh is visible where it is asked about
     */
    public JavaSourceFacts(DSLContext dsl) {
        this(dsl, null);
    }

    /**
     * The same writer under an observation, which is a dev session's: the session watches the
     * source roots, so it can say which files have not moved since this store read them and this
     * writer can skip hashing those.
     *
     * @param observation what the session is watching and what it has seen move, or null for a
     *                    caller watching nothing, which hashes every walked file exactly as before
     */
    public JavaSourceFacts(DSLContext dsl, Observation observation) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.observation = observation;
    }

    /**
     * Declares this gatherer's corpus to the observation: the roots it lives under, and the fold
     * from a changed path to the key of the row this writer stamps, which is the file itself.
     *
     * <p>File grain, which is the finest a fold can be and therefore the one on the losing side of
     * the law {@link Observation#register} states: a dropped event leaves one file stale rather
     * than healing on the next read of its container. It earns that within one process, an editor
     * writing a file moving it through the watcher and a branch switch moving every file it
     * touches, and the escape hatch is the recovery for an operating system that drops events.
     * Coarsening the fold here would re-read the whole population and buy nothing.
     *
     * <p>Called before the pass takes its instant rather than after, so a save arriving while the
     * walk is running has a fold to land on. Registration establishes no floor, so calling it early
     * (a session's startup, before its watchers are up) costs nothing and risks nothing.
     */
    public void register(List<Path> sourceRoots) {
        if (observation != null) {
            observation.register(CORPUS, sourceRoots, path -> path.toString());
        }
    }

    /**
     * The instant a refresh writes into every row it verifies, taken before the walk that reads the
     * files, because the walk's parse is a read of the same content the rows describe.
     *
     * <p>The caller takes it rather than {@link #refresh} because the caller runs the walk. A value
     * taken after the walk would date rows for content already read and swallow every change that
     * landed while it was running.
     */
    public LocalDateTime beginPass() {
        return observation != null
            ? observation.pass(CORPUS)
            : LocalDateTime.now();
    }

    /**
     * Brings the family up to date with one walk: rewrites the files whose content changed, dates
     * every file it read, prunes the files that left the walked roots, and leaves everything else
     * alone.
     *
     * <p>Under an observation a file the session can vouch for is not hashed at all, which on a
     * warm store is most of them. Every file that <em>was</em> hashed carries {@code readAt} away
     * with it, on both arms: through the transaction {@link #rewrite} opens where the content
     * differed, and through one batched update where it matched. A file the store refused to
     * rewrite is on neither arm and keeps the date it had, so it is read again next pass rather
     * than skipped for the rest of the session.
     *
     * @param sourceRoots the roots the walk covered, normalised; the scope this refresh prunes
     *                    within, which is why it is passed rather than derived from the parsed
     *                    files (a root whose every file was deleted appears in no parsed file)
     * @param parsed      the walk's product, one entry per source file it read
     * @param readAt      when this pass began, from {@link #beginPass} and taken before the walk
     * @return what the pass cost, by population
     */
    public Round refresh(List<Path> sourceRoots, List<SourceWalker.ParsedFile> parsed,
                         LocalDateTime readAt) {
        Objects.requireNonNull(readAt, "readAt");
        long startedAt = System.nanoTime();
        register(sourceRoots);
        int hashed = 0;
        int skipped = 0;
        int rewritten = 0;
        try {
            Map<String, Recorded> recorded = new HashMap<>();
            dsl.select(JAVA_FILE.FILE, JAVA_FILE.STAMP, JAVA_FILE.READ_AT).from(JAVA_FILE)
                .forEach(row -> recorded.put(row.value1(), new Recorded(row.value2(), row.value3())));
            var seen = new LinkedHashSet<String>();
            var verified = new ArrayList<String>();
            for (SourceWalker.ParsedFile file : parsed) {
                String name = file.file().toString();
                seen.add(name);
                Recorded known = recorded.get(name);
                if (known != null && observation != null
                    && observation.trusts(CORPUS, name, known.readAt())) {
                    skipped++;
                    continue;
                }
                String stamp = ClasspathSources.hash(file.file());
                hashed++;
                if (stamp == null) {
                    // Readable enough to walk, unreadable now. Its rows describe the content this
                    // store last read, which is the best thing available to say about it, and it
                    // is deliberately not dated: nothing was read, so nothing was established.
                    continue;
                }
                if (known != null && stamp.equals(known.stamp())) {
                    verified.add(name);
                    continue;
                }
                rewritten++;
                dsl.transaction(tx -> rewrite(tx.dsl(), file, stamp, readAt));
            }
            date(verified, readAt);
            prune(normalised(sourceRoots), seen);
        } catch (DataAccessException e) {
            LOG.warn("java source declarations could not be written to the fact store;"
                + " store-side readers answer from the declarations already there", e);
        }
        return new Round(hashed, skipped, rewritten,
            (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /** What the store holds about one file: what it hashed to, and when that read began. */
    private record Recorded(String stamp, LocalDateTime readAt) { }

    /**
     * What one refresh did, by population. The count that matters is what was <em>not</em> hashed:
     * a pass that skips everything is the observation working, and a pass that hashes a whole tree
     * nothing touched is it regressing. Nothing here fails a build, so without the report a
     * regression is invisible, the loop still answering correctly and only slowly.
     *
     * <p>Counts rather than a duration threshold, deliberately: a timing assertion passes on
     * hardware fast enough to hide the regression, and these are the same fact stated as counts.
     */
    public record Round(int hashed, int skipped, int rewritten, long millis) {

        /** Whether this pass verified nothing by reading, which a fully-observed one does. */
        public boolean hashedNothing() {
            return hashed == 0;
        }

        /** The sentence a caller logs. The caller logs; this class reports. */
        public String report() {
            var line = new StringBuilder("source facts: ")
                .append(hashed + skipped).append(" files in ").append(millis).append(" ms, ");
            if (hashedNothing()) {
                line.append("nothing re-hashed");
            } else {
                line.append(hashed).append(" of ").append(hashed + skipped).append(" hashed, ")
                    .append(rewritten).append(" rewritten");
            }
            return line.toString();
        }
    }

    /**
     * Dates the files this pass hashed and found unchanged, in one statement per chunk rather than
     * a transaction per file: they carry the rows they already had, so there is nothing to make
     * atomic beyond the update itself.
     */
    private void date(List<String> verified, LocalDateTime readAt) {
        for (int from = 0; from < verified.size(); from += DATE_CHUNK) {
            var chunk = verified.subList(from, Math.min(from + DATE_CHUNK, verified.size()));
            dsl.update(JAVA_FILE)
                .set(JAVA_FILE.READ_AT, readAt)
                .where(JAVA_FILE.FILE.in(chunk))
                .execute();
        }
    }

    /** Replaces one file's declarations, children before the file row they hang off. */
    private static void rewrite(DSLContext tx, SourceWalker.ParsedFile file, String stamp,
                                LocalDateTime readAt) {
        String name = file.file().toString();
        clear(tx, List.of(name));
        tx.insertInto(JAVA_FILE)
            .set(JAVA_FILE.FILE, name)
            .set(JAVA_FILE.SOURCE_ROOT, file.sourceRoot().toString())
            .set(JAVA_FILE.STAMP, stamp)
            .set(JAVA_FILE.READ_AT, readAt)
            .onDuplicateKeyUpdate()
            // The root can move under a file (a root added that nests inside another), and the
            // stamp is the whole point of the row, so both take this walk's answer. The date goes
            // with them: it is when the reading that produced this stamp began.
            .set(JAVA_FILE.SOURCE_ROOT, file.sourceRoot().toString())
            .set(JAVA_FILE.STAMP, stamp)
            .set(JAVA_FILE.READ_AT, readAt)
            .execute();

        var classes = new ArrayList<JavaClassDeclarationRecord>();
        var methods = new ArrayList<JavaMethodDeclarationRecord>();
        var fields = new ArrayList<JavaFieldDeclarationRecord>();
        var declaredClasses = new HashSet<String>();
        var declaredFields = new HashSet<String>();
        var ordinals = new HashMap<String, Integer>();
        for (SourceWalker.Declaration declaration : file.declarations()) {
            switch (declaration) {
                case SourceWalker.Declaration.ClassDecl c -> {
                    if (declaredClasses.add(c.className())) {
                        var record = tx.newRecord(JAVA_CLASS_DECLARATION);
                        record.setFile(name);
                        record.setClassName(c.className());
                        record.setSourceLine(c.line());
                        record.setSourceColumn(c.column());
                        record.setJavadoc(javadocOf(c.javadoc()));
                        classes.add(record);
                    }
                }
                case SourceWalker.Declaration.MethodDecl m -> {
                    var record = tx.newRecord(JAVA_METHOD_DECLARATION);
                    record.setFile(name);
                    record.setClassName(m.className());
                    record.setMethodName(m.methodName());
                    record.setOrdinal(ordinals.merge(
                        m.className() + '\0' + m.methodName(), 1, Integer::sum) - 1);
                    record.setParameterCount(m.parameterCount());
                    record.setSourceLine(m.line());
                    record.setSourceColumn(m.column());
                    record.setJavadoc(javadocOf(m.javadoc()));
                    methods.add(record);
                }
                case SourceWalker.Declaration.FieldDecl f -> {
                    if (declaredFields.add(f.className() + '\0' + f.fieldName())) {
                        var record = tx.newRecord(JAVA_FIELD_DECLARATION);
                        record.setFile(name);
                        record.setClassName(f.className());
                        record.setFieldName(f.fieldName());
                        record.setSourceLine(f.line());
                        record.setSourceColumn(f.column());
                        record.setJavadoc(javadocOf(f.javadoc()));
                        fields.add(record);
                    }
                }
            }
        }
        // Class rows first: the member relations reach the file through them.
        if (!classes.isEmpty()) tx.batchInsert(classes).execute();
        // A member of a class the scan did not name (an anonymous or local class's) has no
        // declaration row to hang off, so it is not a row here either; the scan drops those
        // before this point, and the filter states the invariant rather than relying on it.
        methods.removeIf(record -> !declaredClasses.contains(record.getClassName()));
        fields.removeIf(record -> !declaredClasses.contains(record.getClassName()));
        if (!methods.isEmpty()) tx.batchInsert(methods).execute();
        if (!fields.isEmpty()) tx.batchInsert(fields).execute();
    }

    /** The store's spelling of an absent doc comment: NULL, never the walker's empty string. */
    private static String javadocOf(String javadoc) {
        return javadoc == null || javadoc.isEmpty() ? null : javadoc;
    }

    /**
     * Drops the rows for files under {@code roots} that this walk did not see. The doomed set is
     * computed against the store's own rows rather than expressed as a {@code NOT IN} over every
     * file the walk found, because the walk's set is the large one and the difference is normally
     * empty.
     */
    private void prune(List<String> roots, Set<String> seen) {
        if (roots.isEmpty()) {
            return;
        }
        var doomed = dsl.select(JAVA_FILE.FILE).from(JAVA_FILE)
            .where(JAVA_FILE.SOURCE_ROOT.in(roots))
            .fetch(JAVA_FILE.FILE)
            .stream()
            .filter(file -> !seen.contains(file))
            .toList();
        if (doomed.isEmpty()) {
            return;
        }
        dsl.transaction(tx -> {
            clear(tx.dsl(), doomed);
            tx.dsl().deleteFrom(JAVA_FILE).where(JAVA_FILE.FILE.in(doomed)).execute();
        });
    }

    /** Empties the declaration relations for {@code files}, children first. */
    private static void clear(DSLContext tx, List<String> files) {
        tx.deleteFrom(JAVA_METHOD_DECLARATION)
            .where(JAVA_METHOD_DECLARATION.FILE.in(files)).execute();
        tx.deleteFrom(JAVA_FIELD_DECLARATION)
            .where(JAVA_FIELD_DECLARATION.FILE.in(files)).execute();
        tx.deleteFrom(JAVA_CLASS_DECLARATION)
            .where(JAVA_CLASS_DECLARATION.FILE.in(files)).execute();
    }

    private static List<String> normalised(List<Path> roots) {
        if (roots == null) {
            return List.of();
        }
        return roots.stream()
            .filter(Objects::nonNull)
            .map(root -> root.toAbsolutePath().normalize().toString())
            .distinct()
            .toList();
    }
}
