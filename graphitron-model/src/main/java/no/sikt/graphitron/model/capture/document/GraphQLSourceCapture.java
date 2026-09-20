package no.sikt.graphitron.model.capture.document;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.read.SourceStamp;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.SchemaLoader;
import org.jooq.Condition;
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
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

/**
 * The gatherer that reads the SDL corpus: it parses each configured document once and owns the
 * store's record of what was read.
 *
 * <p>Sole writer of the {@code SCHEMA_FILE} rows of {@code store_source} and of this graph's
 * schema-file claim. Every gatherer below it is handed the documents rather than the
 * configuration, so the corpus is read once and which files a run met has one answer.
 *
 * <p>Every source is in the list whether or not it parsed, which is what lets the gatherers below
 * sweep it: a file that stopped parsing writes nothing, so its stale rows go only if the writers
 * are told it was read at all. The parse failure travels with it, the problem relation having one
 * writer and that writer being below this one.
 *
 * <p>What changed is compared on {@link SourceStamp}'s content hash, so a file rewritten with
 * identical bytes is unchanged and one saved within a clock tick is not missed. Compared against
 * the claim rather than against {@code store_source}, which is store-global and cannot say whether
 * this graph holds rows derived from those bytes.
 *
 * <p>A source row goes when its file no longer exists, not when this reading did not read it: the
 * relation is shared by every kind and is not partitioned by graph. A claim this graph no longer
 * holds is dropped first, so the foreign key from a claim another graph still holds refuses the
 * delete.
 */
public final class GraphQLSourceCapture {

    private GraphQLSourceCapture() {}

    /**
     * One source this reading is accountable for, and what the graph's store owes it.
     *
     * <p>Exhaustive, so a case nobody thought about cannot be the silent one. The rule they serve:
     * a gatherer brings the {@code (graph, source)} scope into line with what the document states.
     * {@link Changed} states a registry. {@link Unparsable} and {@link Dropped} state nothing, so
     * the scope is emptied, which is the same write over an empty registry. {@link Unchanged}
     * already states what the scope holds, and sweeping a scope this reading did not mark would
     * delete exactly the rows the skip meant to keep.
     *
     * <p>A source the graph read last time is here even when the configuration no longer names it,
     * because a document that has stopped saying anything has to be told to the writers to be
     * swept.
     */
    public sealed interface SourceDocument {

        /** The source this is about, named as the store names it. */
        String sourceName();

        /**
         * A source that states something: it parsed, and {@link #registry()} is what it said.
         *
         * <p>Worth a type because it is the predicate the rule above is written in, and because a
         * caller that composes the corpus rather than transcribing it, the schema assembly being
         * the one, wants every registry and does not care which of the two arms carried it.
         */
        sealed interface Stated extends SourceDocument {

            /** What the parser made of this source. */
            TypeDefinitionRegistry registry();
        }

        /**
         * A source whose bytes differ from the ones this graph's rows were derived from, which
         * includes a source this graph has never read.
         */
        record Changed(String sourceName, TypeDefinitionRegistry registry) implements Stated {}

        /**
         * A source whose bytes are the ones this graph's rows were already derived from.
         *
         * <p>Per graph, not per source: the same file read by two graphs is unchanged for whichever
         * of them last transcribed these bytes and changed for the other, so the comparison is
         * against {@code store_graph_source}'s stamp rather than the shared one on
         * {@code store_source}.
         */
        record Unchanged(String sourceName, TypeDefinitionRegistry registry) implements Stated {}

        /**
         * A source the configuration names and the parser refused.
         *
         * <p>Its scope is emptied rather than left standing, the rows of the last good parse having
         * stopped being what the file says. No stamp is recorded for it, so the next reading
         * attempts it again instead of mistaking a still-broken file for a settled one.
         */
        record Unparsable(String sourceName) implements SourceDocument {}

        /**
         * A source this graph read last time and does not read now: the file is gone, or the
         * configuration has stopped naming it.
         *
         * <p>One arm for both because they are one fact to every writer, the document being out of
         * the corpus either way. Only the reclamation below tells them apart, a file still on disk
         * being a source another graph may hold.
         */
        record Dropped(String sourceName) implements SourceDocument {}
    }

    /**
     * Reads {@code graph}'s configured documents and records what was read, oldest file first.
     *
     * <p>The order is the contract, not an accident of the directory walk: a gatherer reducing
     * these into one registry settles a collision in favour of the older declaration. A source
     * that would not parse holds its own file's place, being a file with a modification time
     * either way. Bound by a test, nothing else noticing if it changed.
     *
     * <p>The instant is the reading's: the rows this writes carry it and the sweeps tell readings
     * apart by it.
     */
    public static List<SourceDocument> capture(DSLContext dsl, GraphIdentity graph,
                                               SubjectConfig config, LocalDateTime readAt) {
        // Read before anything is written, the question being what this graph's rows were derived
        // from rather than what this reading is about to say they were.
        var held = heldStamps(dsl, graph.name());
        var parse = SchemaLoader.parsePerSource(config.schemaFiles(graph.baseDir()));
        var documents = new ArrayList<SourceDocument>();
        for (var source : parse.perSource()) {
            documents.add(read(dsl, graph.name(), source.sourceName(), source.registry(), held, readAt));
        }
        for (var failure : parse.failures()) {
            documents.add(read(dsl, graph.name(), failure.sourceName(), null, held, readAt));
        }
        var configured = new LinkedHashSet<String>();
        documents.forEach(document -> configured.add(document.sourceName()));
        for (String name : held.keySet()) {
            if (!configured.contains(name)) {
                documents.add(new SourceDocument.Dropped(name));
            }
        }
        dropMembership(dsl, graph.name(), configured);
        GraphQLSchemaProblems.writeParsed(dsl, graph.name(), parse.failures(), readAt);
        documents.sort(OLDEST_FIRST);
        return List.copyOf(documents);
    }

    /**
     * What each of this graph's schema files was last transcribed from, by source name.
     *
     * <p>Scoped to the kind this gatherer owns: the classpath and the catalog have memberships in
     * the same relation, and a reading of the SDL corpus has nothing to say about either.
     */
    private static Map<String, String> heldStamps(DSLContext dsl, String graph) {
        var m = STORE_GRAPH_SOURCE;
        var s = STORE_SOURCE;
        return dsl.select(m.SOURCE_NAME, m.STAMP)
            .from(m).join(s).on(s.SOURCE_NAME.eq(m.SOURCE_NAME))
            .where(m.GRAPH_NAME.eq(graph))
            .and(s.SOURCE_KIND.eq("SCHEMA_FILE"))
            .fetchMap(m.SOURCE_NAME, m.STAMP);
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
    private static SourceDocument read(DSLContext dsl, String graph, String sourceName,
                                       TypeDefinitionRegistry registry,
                                       Map<String, String> held, LocalDateTime readAt) {
        var t = STORE_SOURCE;
        String stamp = SourceStamp.ofFile(Path.of(sourceName));
        var mtime = modifiedAt(sourceName);
        dsl.insertInto(t, t.SOURCE_NAME, t.SOURCE_KIND, t.STAMP, t.MTIME, t.LAST_SEEN, t.READ_AT)
            .values(sourceName, "SCHEMA_FILE", stamp, mtime, readAt, readAt)
            .onDuplicateKeyUpdate()
            .set(t.STAMP, stamp)
            .set(t.MTIME, mtime)
            .set(t.LAST_SEEN, readAt)
            .set(t.READ_AT, readAt)
            .execute();
        // A file that would not parse records no stamp, so the next reading meets it as changed
        // and attempts it again rather than reading a still-broken file as a settled one. The
        // claim is still made: this reading is about to empty the scope, which is a transcription
        // of what the file now says.
        if (registry == null) {
            claimMembership(dsl, graph, sourceName, null, readAt);
            return new SourceDocument.Unparsable(sourceName);
        }
        // Unknown counts as changed: a source this graph has not transcribed, or one whose bytes
        // could not be hashed, is not one this reading may skip on the strength of a comparison.
        if (stamp != null && stamp.equals(held.get(sourceName))) {
            // The claim stands untouched, rows and all. Restamping it here would date a
            // transcription this reading is about to decide not to perform.
            return new SourceDocument.Unchanged(sourceName, registry);
        }
        claimMembership(dsl, graph, sourceName, stamp, readAt);
        return new SourceDocument.Changed(sourceName, registry);
    }

    /**
     * This graph's claim on one source, carrying the bytes its rows are derived from and when.
     *
     * <p>The stamp is the graph's rather than the file's: {@code store_source} holds what the file
     * last hashed to for whoever read it, and only this answers whether this graph's scope is
     * current. A stamp that could not date itself would license a skip against an answer of
     * unknown age.
     */
    private static void claimMembership(DSLContext dsl, String graph, String sourceName,
                                        String stamp, LocalDateTime readAt) {
        var m = STORE_GRAPH_SOURCE;
        dsl.insertInto(m, m.GRAPH_NAME, m.SOURCE_NAME, m.STAMP, m.READ_AT)
            .values(graph, sourceName, stamp, readAt)
            .onDuplicateKeyUpdate()
            .set(m.STAMP, stamp)
            .set(m.READ_AT, readAt)
            .execute();
    }

    /**
     * The memberships this graph has stopped holding.
     *
     * <p>Deleted rather than cleared and rewritten, the row carrying a stamp: clearing would throw
     * away what this graph last transcribed and make every source look new next reading. Scoped to
     * the kind this gatherer owns. Dropping the claim first is also what makes the reclamation
     * below safe, a source another graph still claims keeping a reference that refuses the delete.
     */
    private static void dropMembership(DSLContext dsl, String graph, Set<String> configured) {
        var m = STORE_GRAPH_SOURCE;
        var s = STORE_SOURCE;
        Condition scope = m.GRAPH_NAME.eq(graph)
            .and(m.SOURCE_NAME.in(dsl.select(s.SOURCE_NAME).from(s)
                .where(s.SOURCE_KIND.eq("SCHEMA_FILE"))));
        if (!configured.isEmpty()) {
            scope = scope.and(m.SOURCE_NAME.notIn(configured));
        }
        dsl.deleteFrom(m).where(scope).execute();
    }

    /**
     * The sources whose files are gone, forgotten once the gatherers have swept what they said.
     *
     * <p>Called by the pass rather than by {@link #capture}, and the order is the point: a dropped
     * source is still named in the list the gatherers walk, so reclaiming first would cut the
     * provenance out from under rows that had not yet been told to go.
     *
     * <p>Not the sources this reading did not read. The relation is shared and ungraphed, so only
     * absence from the filesystem says a schema file has stopped being one; the bundled directive
     * vocabulary is a source with a row and no file, and a rule reading the filesystem alone would
     * reclaim it every run.
     */
    public static void reclaim(DSLContext dsl, List<SourceDocument> documents) {
        var t = STORE_SOURCE;
        var wasRead = new LinkedHashSet<String>();
        for (var document : documents) {
            if (!(document instanceof SourceDocument.Dropped)) {
                wasRead.add(document.sourceName());
            }
        }
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
