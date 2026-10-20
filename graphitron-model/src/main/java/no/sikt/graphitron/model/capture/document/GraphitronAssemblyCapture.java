package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.derive.FieldPathApplications;
import no.sikt.graphitron.model.derive.FieldPathResolutions;
import no.sikt.graphitron.model.derive.FieldSourceTables;
import no.sikt.graphitron.model.derive.FieldTargetColumns;
import no.sikt.graphitron.model.derive.FieldTargetTables;
import no.sikt.graphitron.model.derive.FieldReferenceStepHops;
import no.sikt.graphitron.model.derive.FieldReferenceStepTargets;
import no.sikt.graphitron.model.derive.FieldTableLinks;
import no.sikt.graphitron.model.derive.Fetchers;
import no.sikt.graphitron.model.derive.NodeIdCandidates;
import no.sikt.graphitron.model.derive.NodeKeyColumns;
import no.sikt.graphitron.model.derive.ResolvedTypeBindings;
import no.sikt.graphitron.model.derive.SpelledTables;
import no.sikt.graphitron.model.derive.TypeBackings;
import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET_RULE;
import static org.jooq.impl.DSL.excluded;
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
        // First of the stages: it reads the transcription alone, and the written order of a field's
        // applications is what everything below that walks a chain wants.
        FieldPathApplications.derive(dsl, graph);
        // Over the nodes the anchor settled.
        NodeKeyColumns.derive(dsl, graph);
        NodeIdCandidates.derive(dsl, graph);
        targets(dsl, graph, readAt);
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
        // Then which class stands for each type: the table binding just written read through its
        // record class, and what the producers' resolved methods ground, closed over the members
        // each backed class offers. The classpath was read before this gatherer ran.
        TypeBackings.derive(dsl, graph, readAt);
        FieldReferenceStepTargets.derive(dsl, graph);
        // The types whose tables each field's statements land on, off the targets and bindings above.
        FieldTargetTables.derive(dsl, graph, readAt);
        // And the tables their reads depart from, off the same bindings and the targets.
        FieldSourceTables.derive(dsl, graph, readAt);
        // The column each scalar with no path reads on those departing tables.
        FieldTargetColumns.derive(dsl, graph, readAt);
        // Then walking the links of each chain against the stages above: a link's departure is the
        // previous link's arrival, and a routine link's arrival is the function its name resolves
        // to. Stored rather than left a view because two readers walk it, the line below and the
        // entry defects at the tail of the derivation stratum.
        FieldPathResolutions.derive(dsl, graph, readAt);
        // Keeping the one reading per link that both walks reach.
        FieldTableLinks.derive(dsl, graph, readAt);
        // Last, each output field's fetcher, off the targets, bindings and backings above.
        Fetchers.derive(dsl, graph, readAt);
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
