package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import org.jooq.DSLContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

/**
 * Reads a graph's schema files, writes what each one says, and records what went wrong
 * saying it.
 *
 * <p>Handed the configuration rather than a parsed document, so the per-file parses happen here
 * where the rows are keyed by the file they came from. Two documents declaring one name are two
 * rows; a merged registry could only carry one.
 *
 * <p>It reads what the author wrote. A tag or note that configuration stamps onto every element of
 * a source is a fact about the build, recorded as one in {@code store_graph_schema_input}, and a
 * consumer wanting the decorated schema joins the two.
 *
 * <p>Two writers per document and one over the corpus, in that order. The AST entries transcribe
 * every node, the graphitron entries decode the directive applications among them onto the rows the
 * first wrote, and the anchors are derived once every document has been read: what a coordinate is
 * cannot be settled by any one file, only by all of them. The decode is a writer per directive site
 * behind one call, an entry referencing the AST row it decodes and those rows living in a different
 * relation at each site.
 */
public final class SdlCapture {

    private SdlCapture() {}

    /**
     * Makes {@code graph}'s SDL rows be what its configured schema files now say, and its problem
     * rows be what reading them raised.
     *
     * <p>The instant is the caller's: the source rows, every entry row and every problem row date
     * the same reading, which is what the sweeps tell readings apart by.
     *
     * <p>The graph's own anchor row is the caller's too. Every row written here holds a foreign key
     * into it and so does every configuration row, and a registry row two gatherers need is not one
     * gatherer's to mint.
     *
     * <p>A source the parser rejected still gets its registry row. It contributes no entries, so
     * without one the file would look like one nobody configured rather than one that would not
     * read.
     */
    public static void capture(DSLContext dsl, GraphIdentity graph, SubjectConfig config,
                               LocalDateTime readAt) {
        var parse = SchemaLoader.parsePerSource(config.schemaFiles(graph.baseDir()));
        for (var document : parse.perSource()) {
            writeSource(dsl, document.sourceName(), readAt);
            SdlEntries.write(dsl, graph.name(), document.sourceName(), document.registry(), readAt);
            GraphitronEntries.write(dsl, graph.name(), document.sourceName(), document.registry(),
                readAt);
        }
        for (var failure : parse.failures()) {
            writeSource(dsl, failure.sourceName(), readAt);
        }
        // After every document, because an anchor is what the corpus says: a coordinate one file
        // stopped declaring is gone only if no other file declares it, which no per-file pass sees.
        SdlAnchor.write(dsl, graph.name(), readAt);
        // What the merge refused and what the assembly refused are the same question asked of the
        // same corpus, so they arrive as one list in the order the stages ran.
        var raised = new ArrayList<>(parse.registryErrors());
        raised.addAll(SchemaAssembly.of(parse.registry()).errors());
        SdlSchemaProblems.write(dsl, graph.name(), parse.failures(), List.copyOf(raised), readAt);
    }

    /**
     * The registry row every one of this file's rows hangs its {@code source_ref} on. Written from
     * the parse's own source name, so the bundled directive vocabulary gets a row on the same terms
     * as an author's file: it is a document this reading read.
     */
    private static void writeSource(DSLContext dsl, String sourceName, LocalDateTime readAt) {
        var t = STORE_SOURCE;
        var mtime = modifiedAt(sourceName);
        dsl.insertInto(t, t.SOURCE_NAME, t.SOURCE_KIND, t.MTIME, t.LAST_SEEN, t.READ_AT)
            .values(sourceName, "SCHEMA_FILE", mtime, readAt, readAt)
            .onDuplicateKeyUpdate()
            .set(t.MTIME, mtime)
            .set(t.LAST_SEEN, readAt)
            .set(t.READ_AT, readAt)
            .execute();
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
