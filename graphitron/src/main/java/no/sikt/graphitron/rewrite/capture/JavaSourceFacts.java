package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.tables.records.JavaClassDeclarationRecord;
import no.sikt.graphitron.model.tables.records.JavaFieldDeclarationRecord;
import no.sikt.graphitron.model.tables.records.JavaMethodDeclarationRecord;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
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
 * The hash is recomputed for every walked file each refresh, deliberately: a modification time is
 * the heuristic {@code store_source}'s stamp exists to avoid, and hashing source files is cheap
 * beside the parse it is protecting.
 *
 * <p>A walk owns the files under the roots it walked, and nothing else. Rows under those roots
 * that the walk did not see are the files that were deleted or renamed, and they are pruned; rows
 * under any other root belong to a walk this one knows nothing about, which in a store shared by
 * a workspace's modules is a sibling module's business. That is what {@code java_file.source_root}
 * is for.
 *
 * <p>Store trouble costs warmth, never the dev loop, on {@link no.sikt.graphitron.rewrite.compile.CompileFacts}'
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

    private final DSLContext dsl;

    /**
     * @param dsl the writer's store handle; in a dev session the session's own, shared with the
     *            readers that answer from it, so a refresh is visible where it is asked about
     */
    public JavaSourceFacts(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    /**
     * Brings the family up to date with one walk: rewrites the files whose content changed, prunes
     * the files that left the walked roots, and leaves everything else alone.
     *
     * @param sourceRoots the roots the walk covered, normalised; the scope this refresh prunes
     *                    within, which is why it is passed rather than derived from the parsed
     *                    files (a root whose every file was deleted appears in no parsed file)
     * @param parsed      the walk's product, one entry per source file it read
     */
    public void refresh(List<Path> sourceRoots, List<SourceWalker.ParsedFile> parsed) {
        try {
            Map<String, String> recorded = new HashMap<>();
            dsl.select(JAVA_FILE.FILE, JAVA_FILE.STAMP).from(JAVA_FILE)
                .forEach(row -> recorded.put(row.value1(), row.value2()));
            var seen = new LinkedHashSet<String>();
            for (SourceWalker.ParsedFile file : parsed) {
                String name = file.file().toString();
                seen.add(name);
                String stamp = ClasspathSources.hash(file.file());
                if (stamp == null) {
                    // Readable enough to walk, unreadable now. Its rows describe the content this
                    // store last read, which is the best thing available to say about it.
                    continue;
                }
                if (stamp.equals(recorded.get(name))) {
                    continue;
                }
                dsl.transaction(tx -> rewrite(tx.dsl(), file, stamp));
            }
            prune(normalised(sourceRoots), seen);
        } catch (DataAccessException e) {
            LOG.warn("java source declarations could not be written to the fact store;"
                + " store-side readers answer from the declarations already there", e);
        }
    }

    /** Replaces one file's declarations, children before the file row they hang off. */
    private static void rewrite(DSLContext tx, SourceWalker.ParsedFile file, String stamp) {
        String name = file.file().toString();
        clear(tx, List.of(name));
        tx.insertInto(JAVA_FILE)
            .set(JAVA_FILE.FILE, name)
            .set(JAVA_FILE.SOURCE_ROOT, file.sourceRoot().toString())
            .set(JAVA_FILE.STAMP, stamp)
            .onDuplicateKeyUpdate()
            // The root can move under a file (a root added that nests inside another), and the
            // stamp is the whole point of the row, so both take this walk's answer.
            .set(JAVA_FILE.SOURCE_ROOT, file.sourceRoot().toString())
            .set(JAVA_FILE.STAMP, stamp)
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
