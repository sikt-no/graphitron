package no.sikt.graphitron.model.capture.document;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.read.SourceStamp;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.SchemaLoader;
import org.jooq.DSLContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

/**
 * The gatherer that reads the SDL corpus: it parses each configured document once and owns the
 * store's record of what was read.
 *
 * <p>It is the only writer of the {@code SCHEMA_FILE} rows of {@code store_source} and of this
 * graph's schema-file membership, and it sweeps exactly those. Every gatherer below it is handed
 * the documents rather than the configuration, so the corpus is read once per capture and the
 * question of which files a run met has one answer.
 *
 * <h2>Every source read is in the list, whether or not it parsed</h2>
 *
 * <p>A document that would not parse has no registry and is in the list anyway, which is what lets
 * the gatherers below sweep it. Each of them marks the rows it writes for a source and deletes that
 * source's rows carrying another instant; a file that stopped parsing writes nothing, so its stale
 * rows go only if the gatherer is told the file was read at all. Leave it out of the list and the
 * rows an author just broke would survive as though they were still true.
 *
 * <p>The failure travels with it for the same reason and one more: the problem relation is keyed on
 * a dense ordinal over all three stages' problems in stage order, so it has one writer, and that
 * writer is below this one. Carrying the failure here is what lets a parse be reported by a
 * gatherer that never saw the parser.
 *
 * <h2>What changed</h2>
 *
 * <p>Each document says whether it differs from what the store last read, compared on
 * {@link SourceStamp}'s content hash rather than on a timestamp, so a file rewritten with identical
 * bytes is unchanged and one edited and saved within a clock tick is not missed. Nothing reads it
 * yet. It is here because it is free where the bytes are already in hand and expensive anywhere
 * else, and because skipping the recapture of an unchanged document is what the flag exists for.
 *
 * <h2>What the sweep takes</h2>
 *
 * <p>A {@code store_source} row goes when its file no longer exists, and not merely when this
 * reading did not read it: the relation is shared by every source kind and is not partitioned by
 * graph, so absence from one graph's configuration says nothing about whether a source is still a
 * source. Its removal nulls the provenance column on the entry stratum, which is what the nineteen
 * {@code source_ref} references are for.
 *
 * <p>Membership is cleared and rewritten for this graph alone, on the terms
 * {@code store_graph_source} states for itself. That ordering is also what makes the row deletion
 * safe: a file this graph has dropped loses its membership first, and a file another graph still
 * claims keeps a reference that refuses the delete. The foreign key is the guard rather than an
 * obstacle, since a source another graph is still reading is not one this run may reclaim.
 */
public final class GraphQLSourceCapture {

    private GraphQLSourceCapture() {}

    /**
     * One source the corpus was read from: the document it parsed to, or the failure that stopped
     * it, and whether its bytes differ from the store's last reading.
     *
     * <p>A source that would not parse carries no registry. A caller that needs the parse asks
     * {@link #parsed()} and skips the rest; a caller sweeping per source needs it not at all and
     * wants the row regardless.
     */
    public record SourceDocument(String sourceName, TypeDefinitionRegistry registry,
                                 boolean changed) {

        /** Whether the parser produced a registry for this source. */
        public boolean parsed() {
            return registry != null;
        }
    }

    /**
     * Reads {@code graph}'s configured documents and records what was read.
     *
     * <p>Sorted, oldest file first, which is this gatherer's contract and not an accident of how the
     * parser happened to walk them. A gatherer reducing these into one registry settles a collision
     * in favour of the older declaration, and it settles it the same way whatever order a directory
     * listed its files in. A source that would not parse sits at its own file's place in the order
     * like any other, since it is a file with a modification time whether or not it read.
     *
     * <p>Bound by a test rather than by this sentence, the order being something a reader depends on
     * across a boundary and nothing else would notice changing.
     *
     * <p>The instant is the reading's, on every gatherer's terms: the rows this writes carry it and
     * the sweep below tells this reading's rows from the last one's by it.
     */
    public static List<SourceDocument> capture(DSLContext dsl, GraphIdentity graph,
                                               SubjectConfig config, LocalDateTime readAt) {
        var parse = SchemaLoader.parsePerSource(config.schemaFiles(graph.baseDir()));
        var documents = new ArrayList<SourceDocument>();
        for (var source : parse.perSource()) {
            documents.add(read(dsl, source.sourceName(), source.registry(), readAt));
        }
        for (var failure : parse.failures()) {
            documents.add(read(dsl, failure.sourceName(), null, readAt));
        }
        membership(dsl, graph.name(), documents);
        reclaimVanished(dsl, documents);
        SdlSchemaProblems.writeParsed(dsl, graph.name(), parse.failures(), readAt);
        documents.sort(OLDEST_FIRST);
        return List.copyOf(documents);
    }

    /**
     * The order the list comes back in: by modification time, then by name where two files share
     * one. A source with no file on disk sorts first, which is where the bundled directive
     * vocabulary belongs: it is older than anything an author wrote, being not written at all.
     */
    private static final Comparator<SourceDocument> OLDEST_FIRST =
        Comparator.<SourceDocument, LocalDateTime>comparing(
                document -> modifiedAt(document.sourceName()),
                Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(SourceDocument::sourceName);

    /**
     * One source's row, and what this reading found there. The stamp is read back before it is
     * written, the comparison being against what the store last held rather than against what this
     * statement is about to put in it.
     */
    private static SourceDocument read(DSLContext dsl, String sourceName,
                                       TypeDefinitionRegistry registry, LocalDateTime readAt) {
        var t = STORE_SOURCE;
        String stamp = SourceStamp.ofFile(Path.of(sourceName));
        String held = dsl.select(t.STAMP).from(t).where(t.SOURCE_NAME.eq(sourceName))
            .fetchOne(t.STAMP);
        var mtime = modifiedAt(sourceName);
        dsl.insertInto(t, t.SOURCE_NAME, t.SOURCE_KIND, t.STAMP, t.MTIME, t.LAST_SEEN, t.READ_AT)
            .values(sourceName, "SCHEMA_FILE", stamp, mtime, readAt, readAt)
            .onDuplicateKeyUpdate()
            .set(t.STAMP, stamp)
            .set(t.MTIME, mtime)
            .set(t.LAST_SEEN, readAt)
            .set(t.READ_AT, readAt)
            .execute();
        // Unknown counts as changed: a source the store has not held, or one whose bytes could not
        // be hashed, is not one a later reading may skip on the strength of this column.
        boolean changed = stamp == null || held == null || !stamp.equals(held);
        return new SourceDocument(sourceName, registry, changed);
    }

    /**
     * This graph's schema-file membership, cleared and rewritten, which is what
     * {@code store_graph_source} says a warm capture does with its own rows. Scoped to the kind
     * this gatherer owns, the catalog's and the classpath's memberships being theirs.
     */
    private static void membership(DSLContext dsl, String graph, List<SourceDocument> documents) {
        var m = STORE_GRAPH_SOURCE;
        var s = STORE_SOURCE;
        dsl.deleteFrom(m)
            .where(m.GRAPH_NAME.eq(graph))
            .and(m.SOURCE_NAME.in(dsl.select(s.SOURCE_NAME).from(s)
                .where(s.SOURCE_KIND.eq("SCHEMA_FILE"))))
            .execute();
        var names = new LinkedHashSet<String>();
        documents.forEach(document -> names.add(document.sourceName()));
        for (String name : names) {
            // Ignoring a duplicate rather than failing on one, because the walk's sink still writes
            // these rows too and a pure key has no payload to disagree about. It goes when the walk
            // does, and until then a second producer is a no-op rather than a collision.
            dsl.insertInto(m, m.GRAPH_NAME, m.SOURCE_NAME).values(graph, name)
                .onDuplicateKeyIgnore().execute();
        }
    }

    /**
     * The sources whose files are gone. Not the sources this reading did not read: the relation is
     * shared and ungraphed, so absence from one graph's configuration says nothing, and only
     * absence from the filesystem says a schema file has stopped being one.
     *
     * <p>A source this reading did read is never a candidate, whatever the filesystem says about
     * it. The bundled directive vocabulary is the case that makes the exemption necessary rather
     * than merely tidy: it is a source with a row and no file, so a rule reading the filesystem
     * alone would reclaim it on every run and take the entry stratum's provenance with it.
     */
    private static void reclaimVanished(DSLContext dsl, List<SourceDocument> read) {
        var t = STORE_SOURCE;
        var wasRead = new LinkedHashSet<String>();
        read.forEach(document -> wasRead.add(document.sourceName()));
        var vanished = dsl.select(t.SOURCE_NAME).from(t)
            .where(t.SOURCE_KIND.eq("SCHEMA_FILE"))
            .fetch(t.SOURCE_NAME).stream()
            .filter(name -> !wasRead.contains(name))
            .filter(name -> !Files.exists(Path.of(name)))
            .toList();
        if (!vanished.isEmpty()) {
            dsl.deleteFrom(t).where(t.SOURCE_NAME.in(vanished)).execute();
        }
    }

    /** When the file was last written, or null for a source that is not a file on disk. */
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
