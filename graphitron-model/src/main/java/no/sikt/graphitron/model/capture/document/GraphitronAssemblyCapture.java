package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.derive.ArgMappingCandidates;
import no.sikt.graphitron.model.derive.BindingClaims;
import no.sikt.graphitron.model.derive.ArgMappingLandings;
import no.sikt.graphitron.model.derive.ArgMappingPairDefects;
import no.sikt.graphitron.model.derive.ConditionShapeDefectMethods;
import no.sikt.graphitron.model.derive.EntryDefectClaims;
import no.sikt.graphitron.model.derive.EntryDefectMembers;
import no.sikt.graphitron.model.derive.OccurrenceProducers;
import no.sikt.graphitron.model.derive.OccurrenceSources;
import no.sikt.graphitron.model.derive.Occurrences;
import no.sikt.graphitron.model.derive.EntryDefects;
import no.sikt.graphitron.model.derive.FieldReferenceStepHops;
import no.sikt.graphitron.model.derive.Fetchers;
import no.sikt.graphitron.model.derive.FieldPaths;
import no.sikt.graphitron.model.derive.NodeKeyColumns;
import no.sikt.graphitron.model.derive.NodeLegacyKeyShapes;
import no.sikt.graphitron.model.derive.OccurrenceCallParameters;
import no.sikt.graphitron.model.derive.OccurrenceColumns;
import no.sikt.graphitron.model.derive.OccurrenceNodeIds;
import no.sikt.graphitron.model.derive.PathClaims;
import no.sikt.graphitron.model.derive.PathNeeds;
import no.sikt.graphitron.model.derive.Paths;
import no.sikt.graphitron.model.derive.PathElements;
import no.sikt.graphitron.model.derive.ProducerClaims;
import no.sikt.graphitron.model.derive.FieldKeyRows;
import no.sikt.graphitron.model.derive.RoutineResults;
import no.sikt.graphitron.model.derive.SpelledTables;
import no.sikt.graphitron.model.derive.Steps;
import no.sikt.graphitron.model.derive.TypeBackings;
import no.sikt.graphitron.model.derive.TypeClaims;
import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.val;

/**
 * The stages that resolve: the tail of the graphitron anchor, what graphitron makes of the store
 * once the documents, the catalog and the classpath have been read.
 *
 * <p>Last in the anchor, and the ordering is a dependency rather than a preference. These stages
 * read the {@code graphql_} anchors for coordinates, the {@code graphitron_} entries for what each
 * directive application said, the {@code sql_} families for the tables and columns a spelling
 * resolves against, and {@code code_} for the methods one names, and several read the
 * coordinate-keyed decode, which {@link no.sikt.graphitron.model.run.ModelCapture} runs ahead of
 * the anchor for that reason.
 *
 * <h2>Why this is not the decode beside it</h2>
 *
 * <p>{@link no.sikt.graphitron.model.capture.graphitron.GraphitronFactCapture} holds the decode, which restates one directive application in
 * graphitron's vocabulary and joins nothing. The two were one class because they share that
 * vocabulary, not because they share a writer or a moment: a decode is a function of one document
 * and runs where the document is, where a resolution needs the store whole and cannot run inside a
 * walk at all.
 *
 * <p>Nothing here buffers. Every stage writes as it goes, which is what lets the sequence be read
 * as the order it states rather than as an order plus a flush somebody has to place correctly.
 */
public final class GraphitronAssemblyCapture {

    private GraphitronAssemblyCapture() {}

    /**
     * Resolves {@code graph}'s facts against everything the reading captured, as the anchor's
     * last step.
     *
     * <p>The instant is the caller's, on every gatherer's terms: the relations these stages mark and
     * sweep tell this reading's rows from the last one's by it.
     */
    public static void resolve(DSLContext dsl, String graph, LocalDateTime readAt) {
        // What each argMapping may write at its site, off the sites the anchor just wrote and the
        // transcription; the views the defects below read over it see this reading's rows.
        ArgMappingCandidates.derive(dsl, graph);
        // Over the nodes the anchor settled.
        NodeKeyColumns.derive(dsl, graph);
        // The legacy key shape each type id answers to, against the current keys just written.
        NodeLegacyKeyShapes.derive(dsl, graph);
        targets(dsl, graph, readAt);
        // What each field's written directives claim it runs, off the anchor's fields and
        // applications; the fetchers and the entry defects below read it.
        ProducerClaims.derive(dsl, graph, readAt);
        // And what each field, input field and argument's written directives claim it binds to, off
        // the same elements and applications; the bindings and their defects read it.
        BindingClaims.derive(dsl, graph, readAt);
        // And what each type's written directives claim it stands for, off the same applications.
        TypeClaims.derive(dsl, graph, readAt);
        // The reference stratum's own resolutions, bottom rung first: what a written table name
        // resolves to against the catalog census, then the hops a @reference path element could
        // take, which read it. Every input either was captured before this gatherer ran or is a
        // plain view over facts that were, so nothing here reads a table a later step writes.
        SpelledTables.derive(dsl, graph);
        // What each path element names, its spelling resolved against the catalog and classpath
        // once, keyed by the element.
        PathElements.derive(dsl, graph, readAt);
        // Where a field's path ends on a @routine, off the targets and the elements just resolved.
        RoutineResults.derive(dsl, graph, readAt);
        FieldReferenceStepHops.deriveKeyed(dsl, graph);
        FieldReferenceStepHops.deriveKeyless(dsl, graph);
        // Every place a field or an argument is reached, off the targets written above and the
        // anchor's table types: a chain grows through a field whose target is an object type with no
        // table. Here rather than as a line of the anchor because the targets are written above; it
        // moves with the other steps when this class dissolves into the anchor's own lines.
        Occurrences.derive(dsl, graph, readAt);
        // Where each written argMapping path lands under each occurrence of its call, a walk over the
        // occurrences just written.
        ArgMappingLandings.derive(dsl, graph, readAt);
        // Where each path is written, by the declared element and the type it reaches, off the path
        // entries and the targets above; the paths follow these.
        PathClaims.derive(dsl, graph, readAt);
        // Then which class stands for each type: the table binding just written read through its
        // record class, and what the producers' resolved methods ground, closed over the members
        // each backed class offers. The classpath was read before this gatherer ran.
        TypeBackings.derive(dsl, graph, readAt);
        // Last, each output field's fetcher, off the targets, bindings and backings above.
        Fetchers.derive(dsl, graph, readAt);
        // How each field under a held object takes its keys off it, off the producers and results
        // just written and the path elements above; the walk departs from these.
        FieldKeyRows.derive(dsl, graph, readAt);
        // Each occurrence's producer, source, needs and paths, a depth at a time, off the
        // occurrences, the key rows and the claims above.
        occurrenceDepths(dsl, graph, readAt);
        // The key columns each node id at an occurrence carries, where the needs just written land,
        // then the column each other scalar occurrence reads or binds there.
        OccurrenceNodeIds.derive(dsl, graph, readAt);
        OccurrenceColumns.derive(dsl, graph, readAt);
        // What fills each parameter of each service call, off the calls, landings and sources above,
        // with the PARAMETER binding of each argument a call takes.
        OccurrenceCallParameters.derive(dsl, graph, readAt);
        // Then each field's path, off the producers and results just written: walked from every
        // table its parent's result is a row of, or from a root routine's own table, one written
        // element at a time.
        FieldPaths.derive(dsl, graph, readAt);
        // Last, the defects this gatherer's facts break, off everything above: the rule's rows
        // marked with this reading, the declarations under each condition shape defect, then the
        // argMapping entries under the strings they fault, then the sweep of this gatherer's codes
        // the reading did not mark.
        EntryDefects.derive(dsl, graph, readAt, EntryDefects.GRAPHITRON);
        ConditionShapeDefectMethods.derive(dsl, graph, readAt);
        EntryDefectClaims.derive(dsl, graph, readAt);
        EntryDefectMembers.derive(dsl, graph, readAt);
        ArgMappingPairDefects.derive(dsl, graph);
        EntryDefects.sweep(dsl, graph, readAt, EntryDefects.GRAPHITRON.gatherer());
    }

    /**
     * What each occurrence executes, where its values come from, the types it must reach and the
     * path to each, one depth at a time, then the sweep of every relation. Within a depth: the
     * producers, which the sources read; the sources, its tables or its class; the needs off those
     * sources; the paths no claim covers, an empty one where the need starts on its landing and an
     * inferred one over the one foreign key; and the claimed needs walked into their steps, each to
     * its written or inherited path or to a stop. A child field or an argument under a field that
     * writes a path starts where that path's join path lands, so a depth reads the join paths of the
     * depth above.
     */
    private static void occurrenceDepths(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var o = GRAPHITRON_OCCURRENCE;
        Integer deepest = dsl.select(max(o.DEPTH)).from(o)
            .where(o.GRAPH_NAME.eq(graph)).and(o.TOUCHED_AT.eq(touchedAt))
            .fetchOne(0, Integer.class);
        if (deepest != null) {
            for (int depth = 0; depth <= deepest; depth++) {
                OccurrenceProducers.writeDepth(dsl, graph, depth, touchedAt);
                OccurrenceSources.writeDepth(dsl, graph, depth, touchedAt);
                PathNeeds.writeDepth(dsl, graph, depth, touchedAt);
                Paths.writeDepth(dsl, graph, depth, touchedAt);
                Steps.writeDepth(dsl, graph, depth, touchedAt);
            }
        }
        OccurrenceProducers.sweep(dsl, graph, touchedAt);
        OccurrenceSources.sweep(dsl, graph, touchedAt);
        PathNeeds.sweep(dsl, graph, touchedAt);
        Paths.sweep(dsl, graph, touchedAt);
        Steps.sweep(dsl, graph, touchedAt);
    }

    /**
     * Each field's target type, over the population the generator emits rather than over the
     * document, which is {@code graphitron_field} and the reason the anchor above it is derived
     * first: the connection's element, with the connection as its wrapper, where the field's named
     * type is a connection, and the named type itself otherwise. The rule is the view beside the
     * target; stored because a reader joins on the target's name and wants an index on that column.
     */
    private static void targets(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var target = GRAPHITRON_FIELD_TARGET;
        var rule = GRAPHITRON_FIELD_TARGET_RULE;
        dsl.insertInto(target)
            .columns(target.GRAPH_NAME, target.TYPE_NAME, target.FIELD_NAME,
                target.TARGET_TYPE_NAME, target.WRAPPER_TYPE_NAME, target.TOUCHED_AT)
            .select(dsl
                .select(rule.GRAPH_NAME, rule.TYPE_NAME, rule.FIELD_NAME,
                    rule.TARGET_TYPE_NAME, rule.WRAPPER_TYPE_NAME,
                    val(touchedAt, target.TOUCHED_AT))
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(target.TARGET_TYPE_NAME,
                excluded(target.TARGET_TYPE_NAME))
            .set(target.WRAPPER_TYPE_NAME,
                excluded(target.WRAPPER_TYPE_NAME))
            .set(target.TOUCHED_AT,
                excluded(target.TOUCHED_AT))
            .execute();
        // The coordinates this reading no longer names, which an upsert cannot find: there is no
        // incoming row to match. A field the author removed goes through the cascade instead.
        dsl.deleteFrom(target)
            .where(target.GRAPH_NAME.eq(graph))
            .and(target.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
