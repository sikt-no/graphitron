package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.sink.Progress;
import no.sikt.graphitron.model.derive.EntryDefects;
import no.sikt.graphitron.model.derive.MintedKeys;
import no.sikt.graphitron.model.derive.VoucherMembers;
import no.sikt.graphitron.model.derive.Nodes;
import no.sikt.graphitron.model.derive.TableTypes;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.run.GraphIdentity;
import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

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
 * <p>And after the catalog and the classpath have been read, which the decode does not need and
 * the anchor does: what an application names is a table or a method, and an anchor run before
 * either was read could only copy the name.
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
     * one reading writes both the transcription and its decode, and a sweep that could not tell them apart would take the
     * decode of an application the reading had just rewritten.
     */
    public static void capture(DSLContext dsl, GraphIdentity graph,
                               List<GraphQLSourceCapture.SourceDocument> documents,
                               LocalDateTime readAt) {
        Progress.started(dsl, graph.name(), GATHERER);
        dsl.transaction(tx -> gather(tx.dsl(), graph, documents, readAt));
        Progress.completed(dsl, graph.name(), GATHERER);
    }

    /** This gatherer's row in {@code store_graph_progress} and {@code meta_gatherer}. */
    static final String GATHERER = "graphitron";

    private static void gather(DSLContext dsl, GraphIdentity graph,
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
                case GraphQLSourceCapture.SourceDocument.Specification specification ->
                    GraphitronAstEntries.write(dsl, graph.name(), specification.sourceName(),
                        specification.registry(), readAt);
                case GraphQLSourceCapture.SourceDocument.Unchanged _,
                     GraphQLSourceCapture.SourceDocument.UnchangedSpecification _ -> { }
                case GraphQLSourceCapture.SourceDocument.Dropped _ ->
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
        AnchorStage.runAll(dsl, graph.name(), readAt, STAGES);
        // The gatherer's, not a stage's: the codes its stages own that this reading did not mark.
        EntryDefects.sweep(dsl, graph.name(), readAt, GATHERER);
    }

    /**
     * The anchor phase's stages, in the order they run, which is {@code meta_stage}'s order for this
     * gatherer: the anchors, then what they mint, then {@link GraphitronAssemblyCapture#STAGES}, the
     * stages that resolve against everything above and the catalog and classpath.
     */
    public static final List<AnchorStage> STAGES = Stream.concat(Stream.of(
        new AnchorStage("anchor", GraphitronAnchor.class, GraphitronAnchor::write),
        // The fields each @issue absorbs into its voucher, from the transcription alone, ahead of the
        // expansion whose authored sets withhold them.
        new AnchorStage("voucher-members", VoucherMembers.class,
            (dsl, graph, readAt) -> VoucherMembers.derive(dsl, graph)),
        // The schema the generator works with, macros expanded: it reads only the transcription and
        // the macros' own views, and every type-grained relation below keys into its types.
        new AnchorStage("macro-expansion", MacroExpansion.class, MacroExpansion::derive),
        // What each written code reference names on the classpath, which this anchor can ask now it
        // runs after the classpath has been read.
        new AnchorStage("code-references", GraphitronCodeReferences.class,
            GraphitronCodeReferences::write),
        // Where each written argMapping sits, which its readers join into views of their own.
        new AnchorStage("argmapping-sites", GraphitronArgMappingSites.class,
            GraphitronArgMappingSites::write),
        // And each pair of each, named by its mapping and the parameter it binds.
        new AnchorStage("argmapping-pairs", GraphitronArgMappingPairs.class,
            GraphitronArgMappingPairs::write),
        // The context key filling each parameter of each call, which finds a pair's call through
        // the sites just written.
        new AnchorStage("call-context-keys", GraphitronCallContextKeys.class,
            GraphitronCallContextKeys::write),
        // Which types are bound to a table and which of those are nodes. Both read the anchors just
        // written and corpora captured before this one, and the mint below reads the second.
        new AnchorStage("table-types", TableTypes.class, (dsl, graph, readAt) -> TableTypes.derive(dsl, graph)),
        new AnchorStage("nodes", Nodes.class, (dsl, graph, readAt) -> Nodes.derive(dsl, graph)),
        // Whether the graph opts in to federation, which the key mint below reads.
        new AnchorStage("federation-link", FederationLinks.class, FederationLinks::link),
        // The keys federation's rules mint, from the nodes and the link just written, ahead of the
        // emitted population whose minted applications render them.
        new AnchorStage("minted-keys", MintedKeys.class, (dsl, graph, readAt) -> MintedKeys.derive(dsl, graph)),
        // The directives applied across the expanded schema, whose minted applications render the
        // nodes and the keys just written.
        new AnchorStage("directive-applications", MacroExpansion.class, MacroExpansion::directives),
        // What the link imports, which is what the author wrote plus what was just applied.
        new AnchorStage("federation-imports", FederationLinks.class, FederationLinks::imports),
        // And the vocabulary the composed schema has, which those imports name.
        new AnchorStage("federation-vocabulary", FederationVocabulary.class,
            FederationVocabulary::derive)),
        GraphitronAssemblyCapture.STAGES.stream()).toList();
}
