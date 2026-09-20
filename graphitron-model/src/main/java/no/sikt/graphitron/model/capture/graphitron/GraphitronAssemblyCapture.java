package no.sikt.graphitron.model.capture.graphitron;

import no.sikt.graphitron.model.capture.macro.MacroCapture;
import no.sikt.graphitron.model.derive.ElementAnchors;
import no.sikt.graphitron.model.derive.FieldChainApplications;
import no.sikt.graphitron.model.derive.FieldEndpoints;
import no.sikt.graphitron.model.derive.FieldReferenceStepHops;
import no.sikt.graphitron.model.derive.FieldReferenceStepTargets;
import no.sikt.graphitron.model.derive.FieldRoutines;
import no.sikt.graphitron.model.derive.FieldTableLinks;
import no.sikt.graphitron.model.derive.NodeKeyColumns;
import no.sikt.graphitron.model.derive.Nodes;
import no.sikt.graphitron.model.derive.ResolvedTypeBindings;
import no.sikt.graphitron.model.derive.SpelledTables;
import no.sikt.graphitron.model.derive.TableTypes;
import no.sikt.graphitron.model.sink.FactSink;
import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION_ELEMENT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_NAVIGATION;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * The stages that resolve: what graphitron makes of the whole store once every corpus in it has
 * been read.
 *
 * <p>Runs last, and the ordering is a dependency rather than a preference. These stages read the
 * {@code graphql_} anchors for coordinates, the {@code graphitron_} entries for what each directive
 * application said, the {@code sql_} families for the tables and columns a spelling resolves
 * against, and {@code code_} for the methods one names. A pass that ran them earlier would resolve
 * against whichever of those it had so far.
 *
 * <p>Reads captured rows and no corpus of its own, which is what lets it cross corpora at all: a
 * crawler's rows about its own corpus may not vary with another's contents, and every rule here is
 * a crossing. That is the same reason it cannot be a stage of the gatherer whose entries it reads.
 *
 * <h2>Why this is not the decode beside it</h2>
 *
 * <p>{@link GraphitronFactCapture} holds the decode, which restates one directive application in
 * graphitron's vocabulary and joins nothing. The two were one class because they share that
 * vocabulary, not because they share a writer or a moment: a decode is a function of one document
 * and runs where the document is, where a resolution needs the store whole and cannot run inside a
 * walk at all.
 *
 * <h2>Its own sink</h2>
 *
 * <p>The expansion mints rows and claims their keys first-wins, so it needs a sink; nothing else
 * here does. The sink is built here rather than taken from a caller, because what it buffers is
 * this sequence's own and the flush below is what publishes it to the stage that reads it. A caller
 * sharing one would be handing over a buffer whose contents it cannot see and whose flush point is
 * not its to choose.
 */
public final class GraphitronAssemblyCapture {

    private GraphitronAssemblyCapture() {}

    /**
     * Resolves {@code graph}'s facts against everything the reading captured.
     *
     * <p>The instant is the caller's, on every gatherer's terms: the relations these stages mark and
     * sweep tell this reading's rows from the last one's by it.
     */
    public static void capture(DSLContext dsl, String graph, LocalDateTime readAt) {
        var sink = new FactSink(dsl, graph, readAt);
        // First of the stages: it reads the transcription alone, and the written order of a field's
        // applications is what everything below that walks a chain wants.
        FieldChainApplications.derive(dsl, graph);
        TableTypes.derive(dsl, graph);
        Nodes.derive(dsl, graph);
        NodeKeyColumns.derive(dsl, graph);
        MacroCapture.expand(sink, dsl, graph);
        // The minted rows reach the store before the anchors below read them. A flush is not a
        // commit: inside the load's transaction it publishes to the next stage and to nothing else.
        sink.flush();
        // The facet half of the same expansion, after that flush and not folded into it: the
        // relation it reads resolves a carrier's facets through the rewrite rows the line above
        // writes, so those rows have to be in the store before it is asked.
        MacroCapture.expandFacets(sink, dsl, graph);
        sink.flush();
        // After the flush, for the reason the method states: the rows have to land before what this
        // reading stopped minting can be told apart from them.
        MacroCapture.sweep(dsl, graph, readAt);
        // The anchors before every stage that keys at a coordinate, because the expansion above is
        // the second arm of their population and everything below reads them rather than the union.
        ElementAnchors.derive(dsl, graph);
        navigation(dsl, graph, readAt);
        // The reference stratum's own resolutions, bottom rung first: what a written table name
        // resolves to against the catalog census, then the hops a @reference path element could
        // take, which read it. Every input either was captured before this gatherer ran or is a
        // plain view over facts that were, so nothing here reads a table a later step writes.
        SpelledTables.derive(dsl, graph);
        FieldReferenceStepHops.deriveKeyed(dsl, graph);
        FieldReferenceStepHops.deriveKeyless(dsl, graph);
        // Then the bindings, which the routine arm reaches through the hops just written, and then
        // the walk, which seeds from those bindings and steps through those hops.
        ResolvedTypeBindings.derive(dsl, graph);
        FieldReferenceStepTargets.derive(dsl, graph);
        // Then the endpoints, because their target rule reads the navigation above and their
        // departure reads the bindings above that.
        FieldEndpoints.derive(dsl, graph, readAt);
        // After it, the applications being keyed by the chain the line above establishes.
        FieldRoutines.derive(dsl, graph, readAt);
        // Last of all, resolving the links of each chain in order against the two stages above it:
        // a link's departure is the previous link's arrival, and a routine link's arrival is what
        // the line above resolved.
        FieldTableLinks.derive(dsl, graph, readAt);
    }

    /**
     * Which type each field's own generated SQL navigates as, over the population the generator
     * emits rather than over the document, which is {@code graphitron_field} and the reason the
     * anchor above it is derived first: the connection's element where the field's named type is
     * a connection, and the named type itself otherwise.
     *
     * <p>Two rungs and not the three this rule used to carry. The retired one took the expression
     * the author wrote wherever a macro had rewritten it, which was the only rung needing something
     * no relation held, and therefore the only reason this rule had to be computed where the parse
     * was. It was also carrying nothing: measured over a consumer-size schema every row it answered
     * is the row the two rungs below it answer, a synthesised connection's {@code edges.node} being
     * the element the authored expression named. Stated as one statement rather than as a view
     * because a reader joins on what it projects and wants an index on that column.
     */
    private static void navigation(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        dsl.insertInto(GRAPHITRON_FIELD_NAVIGATION)
            .columns(GRAPHITRON_FIELD_NAVIGATION.GRAPH_NAME, GRAPHITRON_FIELD_NAVIGATION.TYPE_NAME,
                GRAPHITRON_FIELD_NAVIGATION.FIELD_NAME, GRAPHITRON_FIELD_NAVIGATION.BASIS,
                GRAPHITRON_FIELD_NAVIGATION.NAVIGATED_TYPE_NAME,
                GRAPHITRON_FIELD_NAVIGATION.TOUCHED_AT)
            .select(dsl
                .select(GRAPHITRON_FIELD.GRAPH_NAME, GRAPHITRON_FIELD.TYPE_NAME,
                    GRAPHITRON_FIELD.FIELD_NAME,
                    when(GRAPHITRON_CONNECTION_ELEMENT_TYPE.ELEMENT_TYPE_NAME.isNull(),
                        val("NAMED_TYPE")).otherwise(val("CONNECTION_ELEMENT")),
                    coalesce(GRAPHITRON_CONNECTION_ELEMENT_TYPE.ELEMENT_TYPE_NAME,
                        GRAPHITRON_FIELD.NAMED_TYPE),
                    val(touchedAt, GRAPHITRON_FIELD_NAVIGATION.TOUCHED_AT))
                .from(GRAPHITRON_FIELD)
                .leftJoin(GRAPHITRON_CONNECTION_ELEMENT_TYPE)
                .on(GRAPHITRON_CONNECTION_ELEMENT_TYPE.GRAPH_NAME.eq(GRAPHITRON_FIELD.GRAPH_NAME))
                .and(GRAPHITRON_CONNECTION_ELEMENT_TYPE.TYPE_NAME.eq(GRAPHITRON_FIELD.NAMED_TYPE))
                .where(GRAPHITRON_FIELD.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(GRAPHITRON_FIELD_NAVIGATION.BASIS,
                excluded(GRAPHITRON_FIELD_NAVIGATION.BASIS))
            .set(GRAPHITRON_FIELD_NAVIGATION.NAVIGATED_TYPE_NAME,
                excluded(GRAPHITRON_FIELD_NAVIGATION.NAVIGATED_TYPE_NAME))
            .set(GRAPHITRON_FIELD_NAVIGATION.TOUCHED_AT,
                excluded(GRAPHITRON_FIELD_NAVIGATION.TOUCHED_AT))
            .execute();
        // The coordinates this reading stopped navigating, which an upsert cannot find: there is no
        // incoming row to match. A field the author removed goes through the cascade instead.
        dsl.deleteFrom(GRAPHITRON_FIELD_NAVIGATION)
            .where(GRAPHITRON_FIELD_NAVIGATION.GRAPH_NAME.eq(graph))
            .and(GRAPHITRON_FIELD_NAVIGATION.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
