package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.run.GraphIdentity;
import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Decodes the directive applications the SDL transcription wrote, onto the rows it wrote them as.
 *
 * <p>graphitron's vocabulary is carried by directives, so every fact this writes is a reading of
 * one application: what {@code @table} names, which field a {@code @reference} steps through, what
 * an {@code @error} handler catches. Each row keys into the applied-directive row it decodes, so
 * the decode says nothing the transcription does not already hold a position for, and a decode
 * without its application is not expressible.
 *
 * <p>Runs after {@link GraphQLAstCapture} rather than beside it. The keys it writes into are that
 * gatherer's, so it needs the whole transcription present and swept before it starts, and the
 * anchors it resolves against are settled at the end of that run. What survives there is what this
 * decodes.
 *
 * <h2>The decode is one writer per site</h2>
 *
 * <p>A directive application is decoded where it sits, and the rows it decodes live in a different
 * relation at each site: a type's applications, a field's, an input value's, an enum value's, the
 * schema's. {@link GraphitronEntries} is the one call behind those writers.
 *
 * <p>Anchoring is this gatherer's final step, for the reason it is every gatherer's: what a
 * coordinate resolves to cannot be settled by any one document, only by all of them.
 */
public final class GraphitronAstCapture {

    private GraphitronAstCapture() {}

    /**
     * Makes {@code graph}'s {@code graphitron_ast_} rows and graphitron's own anchors be what the
     * documents now say.
     *
     * <p>The instant is the caller's, and it is the same instant the transcription above ran on:
     * one reading writes both strata, and a sweep that could not tell them apart would take the
     * decode of an application the reading had just rewritten.
     */
    public static void capture(DSLContext dsl, GraphIdentity graph,
                               List<GraphQLSourceCapture.SourceDocument> documents,
                               LocalDateTime readAt) {
        captureEntries(dsl, graph, documents, readAt);
        anchor(dsl, graph, readAt);
    }

    /**
     * The per-document decode alone, each document's rows swept against this reading as it goes.
     *
     * <p>A document that would not parse contributes nothing and is skipped, on the same terms as
     * the transcription it decodes.
     *
     * <p>Separate from {@link #anchor} for the incumbent walk, whose pass runs the two with its own
     * work between them.
     */
    public static void captureEntries(DSLContext dsl, GraphIdentity graph,
                                      List<GraphQLSourceCapture.SourceDocument> documents,
                                      LocalDateTime readAt) {
        for (var document : documents) {
            if (!document.parsed()) {
                continue;
            }
            GraphitronEntries.write(dsl, graph.name(), document.sourceName(), document.registry(),
                readAt);
        }
    }

    /**
     * graphitron's own anchors, resolved out of the entries this reading has just written.
     *
     * <p>After the SDL anchors, which settle which of several declarations the corpus honours;
     * these resolve against what that settled rather than asking the corpus again. They key into
     * {@code graphql_type_element} and {@code graphql_type_declaration}, so they run after whichever
     * producer of those the pass has.
     */
    public static void anchor(DSLContext dsl, GraphIdentity graph, LocalDateTime readAt) {
        GraphitronAnchor.write(dsl, graph.name(), readAt);
    }
}
