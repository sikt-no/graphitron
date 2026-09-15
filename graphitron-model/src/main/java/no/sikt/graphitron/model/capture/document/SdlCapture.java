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

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
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
        var parse = captureFacts(dsl, graph, config, readAt);
        // What the merge refused and what the assembly refused are the same question asked of the
        // same corpus, so they arrive as one list in the order the stages ran.
        var raised = new ArrayList<>(parse.registryErrors());
        raised.addAll(SchemaAssembly.of(parse.registry()).errors());
        SdlSchemaProblems.write(dsl, graph.name(), parse.failures(), List.copyOf(raised), readAt);
    }

    /**
     * {@link #capture} without the problem rows: what this gatherer writes about the corpus, and
     * nothing about whether the corpus assembled.
     *
     * <p>The split is worth having on its own terms rather than only for the caller it was cut for.
     * Raising the problems means assembling, and a corpus an author is mid-edit on may refuse to
     * assemble while every fact this writes about it is still true; a reading that wants the facts
     * should not have to survive the assembly to get them.
     */
    public static SchemaLoader.PerSourceParse captureFacts(DSLContext dsl, GraphIdentity graph,
                                                           SubjectConfig config,
                                                           LocalDateTime readAt) {
        var parse = captureEntries(dsl, graph, config, readAt);
        // After every document, because an anchor is what the corpus says: a coordinate one file
        // stopped declaring is gone only if no other file declares it, which no per-file pass sees.
        // Its sweep takes the graphitron rows hanging off a coordinate it drops, those references
        // cascading, which is what lets this run before them rather than behind an emptying pass.
        SdlAnchor.write(dsl, graph.name(), readAt);
        captureAstIndex(dsl, graph, readAt);
        captureGraphitronAnchors(dsl, graph, readAt);
        return parse;
    }

    /**
     * The per-document half alone: every source's registry row and both entry writers over it,
     * with the parse returned so a caller wanting more of this reading pays for one parse.
     *
     * <p>Public because the incumbent pass has to run it. A relation that has moved off that pass's
     * walk is written by this gatherer and by nothing else, so a store captured through the walk
     * would hold nothing where its readers expect the moved relation. Split from the rest rather
     * than offered whole for two reasons the walk makes true: running the face there would report
     * one assembly's problems twice, and the {@code graphql_} anchors have two producers still, so
     * a second one running in the same pass would meet the walk's own inserts on their keys. What
     * this half writes meets nothing: the entry stratum is keyed by written position and the walk
     * does not write a row of it.
     */
    public static SchemaLoader.PerSourceParse captureEntries(DSLContext dsl, GraphIdentity graph,
                                                             SubjectConfig config,
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
        return parse;
    }

    /**
     * The entry stratum's own index alone: every written position it holds, with the element that
     * encloses each.
     *
     * <p>Its own step rather than a line inside the anchor writer, and public for the same reason
     * {@link #captureGraphitronAnchors} is: two passes read this relation and only one of them
     * writes the SDL anchors. The walk's pass writes those itself and therefore skips that writer
     * whole, so an index written inside it was written by one pass of the two, and the stages below
     * the walk that reference the index found no row to reference. A reading has to write what its
     * own stages read.
     *
     * <p>After the element anchors in whichever pass, because every row carries a foreign key into
     * one. It needs them present and reads none of them: an entry's enclosing element comes from
     * the coordinate its own relation generates, or from the index row its parent already has.
     *
     * <p>Sweeps what this reading did not write, which travels with the write for the reason every
     * sweep here does: a reading that wrote the rows is the only one that can say which rows are
     * stale. Its own parent edge and the graphitron rows keyed into it cascade.
     */
    public static void captureAstIndex(DSLContext dsl, GraphIdentity graph, LocalDateTime readAt) {
        AstEntries.write(dsl, graph.name(), readAt);
        dsl.deleteFrom(GRAPHQL_AST_ENTRY)
            .where(GRAPHQL_AST_ENTRY.GRAPH_NAME.eq(graph.name()))
            .and(GRAPHQL_AST_ENTRY.TOUCHED_AT.ne(readAt))
            .execute();
    }

    /**
     * graphitron's own anchors alone, resolved out of the entries the reading already wrote.
     *
     * <p>Public for the same caller and apart from the SDL anchors for the reason above: that
     * caller's walk writes the {@code graphql_} ones itself, and these do not duplicate anything
     * it writes. They read them, though, keying into {@code graphql_type_element} and
     * {@code graphql_type_declaration}, so this runs after whichever producer of those the pass
     * has rather than beside the entries.
     */
    public static void captureGraphitronAnchors(DSLContext dsl, GraphIdentity graph,
                                                LocalDateTime readAt) {
        // After the SDL anchors, which settle which of several declarations the corpus honours;
        // graphitron's own anchors resolve against what that settled rather than asking again.
        GraphitronAnchor.write(dsl, graph.name(), readAt);
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
