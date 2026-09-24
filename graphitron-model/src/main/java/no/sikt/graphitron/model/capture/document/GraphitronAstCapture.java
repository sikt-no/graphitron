package no.sikt.graphitron.model.capture.document;

import graphql.schema.idl.TypeDefinitionRegistry;
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
 * schema's. {@link GraphitronAstEntries} is the one call behind those writers.
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
     * <p>Exhaustive over what the reading found, because every arm is a thing to do rather than
     * three and a skip. A document that states a registry has its scope rewritten from it. A
     * document that states nothing, having failed to parse or left the corpus, has its scope
     * emptied, and an empty registry is how it is said: the writer walks no declarations, marks
     * nothing, and the sweep it already ends in takes the rows the document used to justify.
     * A document that states what the scope already holds is left alone, sweeping a scope this
     * reading did not mark being the one way to delete rows that are still true.
     *
     * <p>Separate from {@link #anchor} for the incumbent walk, whose pass runs the two with its own
     * work between them.
     */
    public static void captureEntries(DSLContext dsl, GraphIdentity graph,
                                      List<GraphQLSourceCapture.SourceDocument> documents,
                                      LocalDateTime readAt) {
        for (var document : documents) {
            switch (document) {
                case GraphQLSourceCapture.SourceDocument.Changed changed ->
                    GraphitronAstEntries.write(dsl, graph.name(), changed.sourceName(),
                        changed.registry(), readAt);
                case GraphQLSourceCapture.SourceDocument.Unchanged _ -> { }
                case GraphQLSourceCapture.SourceDocument.Unparsable _,
                     GraphQLSourceCapture.SourceDocument.Dropped _ ->
                    GraphitronAstEntries.write(dsl, graph.name(), document.sourceName(),
                        new TypeDefinitionRegistry(), readAt);
            }
        }
    }

    /**
     * graphitron's own anchors, resolved out of the entries this reading has just written.
     *
     * <p>After the SDL anchors, which settle which of several declarations the corpus honours;
     * these resolve against what that settled rather than asking the corpus again. They key into
     * {@code graphql_type_element} and {@code graphql_type_declaration}, so they run after whichever
     * producer of those the pass has.
     *
     * <p>The emitted population is the last step and not a pass of its own. What a macro mints is
     * derived from the application that coins it, and an application is a thing written at a
     * position in a document, so the reading that decodes the document is the reading that knows
     * it. Expanding somewhere later meant reading a coordinate the corpus had already resolved,
     * which is a grain with no application in it and nothing to tell two applications apart by.
     */
    public static void anchor(DSLContext dsl, GraphIdentity graph, LocalDateTime readAt) {
        GraphitronAnchor.write(dsl, graph.name(), readAt);
        // Then what the applications just anchored mint. Second by our choice rather than by
        // necessity of the entries: at the entry grain an @asConnection and an @asFacet are
        // siblings, each decoded from its own position and reading nothing of the other. It is
        // here that one depends on the other, the emitted population being resolved out of the
        // applications this gatherer settled a statement earlier.
        EmittedAnchor.derive(dsl, graph.name(), readAt);
    }
}
