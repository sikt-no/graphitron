package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.run.GraphIdentity;
import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;

/**
 * Transcribes what each schema document says, node by node, and anchors what the corpus says once
 * every document has been read.
 *
 * <p>Handed the documents rather than the configuration: the corpus is read by
 * {@link GraphQLSourceCapture} and read once, so this gatherer's subject is a parse somebody else
 * already paid for. Its rows are keyed by written position, which is why they are per document and
 * not per merged registry. Two documents declaring one name are two rows; a merged registry could
 * only carry one.
 *
 * <p>It records what the author wrote. A tag or note that configuration stamps onto every element
 * of a source is a fact about the build, recorded as one in {@code store_graph_schema_input}, and a
 * consumer wanting the decorated schema joins the two.
 *
 * <h2>Transcription, then the sweep, then the anchors</h2>
 *
 * <p>Anchoring is this gatherer's final step, after its own mark and sweep, because an anchor is
 * what the corpus says rather than what a file says. A coordinate one document stopped declaring is
 * gone only if no other document declares it, and no per-document pass can see that. Running it
 * before the sweep would anchor coordinates the reading has already stopped finding.
 *
 * <p>The anchors' own sweep takes the graphitron rows hanging off a coordinate it drops, those
 * references cascading. That is what lets this gatherer run ahead of the decode rather than behind
 * an emptying pass.
 */
public final class GraphQLAstCapture {

    private GraphQLAstCapture() {}

    /**
     * Makes {@code graph}'s {@code graphql_ast_} rows and its {@code graphql_} anchors be what the
     * documents now say.
     *
     * <p>The instant is the caller's: every row this writes dates the same reading, which is what
     * the sweeps tell readings apart by.
     *
     * <p>The graph's own anchor row is the caller's too. Every row written here holds a foreign key
     * into it, and a registry row several gatherers need is not one gatherer's to mint.
     */
    public static void capture(DSLContext dsl, GraphIdentity graph,
                               List<GraphQLSourceCapture.SourceDocument> documents,
                               LocalDateTime readAt) {
        captureEntries(dsl, graph, documents, readAt);
        anchor(dsl, graph, readAt);
    }

    /**
     * The per-document transcription alone, each document's rows swept against this reading as it
     * goes.
     *
     * <p>A document that would not parse contributes nothing and is skipped. It is still in the
     * list, and being in the list is what lets the writers below it sweep the rows an author just
     * broke instead of leaving them standing as though they were still true.
     *
     * <p>Separate from {@link #anchor} because the incumbent walk needs one and writes the other
     * itself. What this half writes meets nothing that pass produces, the entry stratum being keyed
     * by written position and the walk writing no row of it.
     */
    public static void captureEntries(DSLContext dsl, GraphIdentity graph,
                                      List<GraphQLSourceCapture.SourceDocument> documents,
                                      LocalDateTime readAt) {
        for (var document : documents) {
            if (!document.parsed()) {
                continue;
            }
            SdlEntries.write(dsl, graph.name(), document.sourceName(), document.registry(), readAt);
        }
    }

    /**
     * What the corpus says, derived once every document has been transcribed: the elements a
     * coordinate names, and the index of every written position that names one.
     *
     * <p>Public apart from {@link #capture} for the incumbent walk, which writes the
     * {@code graphql_} anchors itself and would meet these on their keys. That pass still needs the
     * index, so the index is reachable on its own below.
     */
    public static void anchor(DSLContext dsl, GraphIdentity graph, LocalDateTime readAt) {
        SdlAnchor.write(dsl, graph.name(), readAt);
        captureAstIndex(dsl, graph, readAt);
    }

    /**
     * The entry stratum's own index alone: every written position it holds, with the element that
     * encloses each.
     *
     * <p>Reachable on its own because two passes read this relation and only one of them writes the
     * SDL anchors. The walk's pass writes those itself and therefore skips the step above whole, so
     * an index written only inside it would be written by one pass of the two, and the stages below
     * the walk that reference the index would find no row to reference. A reading has to write what
     * its own stages read.
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
}
