package no.sikt.graphitron.model.capture;

import no.sikt.graphitron.model.derive.ArgMappingCandidates;
import no.sikt.graphitron.model.derive.ArgumentColumnMatches;
import no.sikt.graphitron.model.derive.ArgumentColumnScopes;
import no.sikt.graphitron.model.derive.ArgumentReferenceStepTargets;
import no.sikt.graphitron.model.derive.ArgumentScopeTables;
import no.sikt.graphitron.model.derive.AuthoredClaimRejectionRows;
import no.sikt.graphitron.model.derive.CarrierDataFields;
import no.sikt.graphitron.model.derive.ClassificationDomainCapture;
import no.sikt.graphitron.model.derive.FieldColumnScopes;
import no.sikt.graphitron.model.derive.FieldScopeTables;
import no.sikt.graphitron.model.derive.InputFieldCarrierRoles;
import no.sikt.graphitron.model.derive.InputFieldColumnMatches;
import no.sikt.graphitron.model.derive.InputFieldFilterRoles;
import no.sikt.graphitron.model.derive.InputFieldReferenceStepTargets;
import no.sikt.graphitron.model.derive.InputFieldResolvingTables;
import no.sikt.graphitron.model.derive.InputOccurrencePaths;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.derive.MutationPayloadColumns;
import no.sikt.graphitron.model.derive.MutationPayloadKeyMemberships;
import no.sikt.graphitron.model.derive.MutationPayloadRefusals;
import no.sikt.graphitron.model.derive.MutationWriteDestinations;
import no.sikt.graphitron.model.derive.MutationWritePayloads;
import no.sikt.graphitron.model.derive.NodeIdDecodeColumns;
import no.sikt.graphitron.model.derive.NodeIdDecodeHopColumns;
import no.sikt.graphitron.model.derive.NodeIdDecodeHops;
import no.sikt.graphitron.model.derive.NodeIdInstructions;
import no.sikt.graphitron.model.derive.RefreshProgress;
import no.sikt.graphitron.model.derive.TypeBackingRows;
import no.sikt.graphitron.model.derive.UnlowerableOrderingRejectionRows;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;


/**
 * The derivations the capture pass leaves behind: the catalog's key closure, the classification
 * domain, four hand-written derivations and the registered materialisations.
 *
 * <p>It captures nothing. Every fact it used to write is written by
 * {@link no.sikt.graphitron.model.run.ModelCapture}, which runs these at its tail because each
 * reads what that pass wrote. The store is the caller's, opened and closed by a mojo or a test.
 *
 * @deprecated the last of the walk, and it goes relation by relation. Each derivation here
 *     leaves as its rows come to be written where they are read from: the hand-written four
 *     when their rules can be stated as registered materializations, and the refresh when a
 *     store can state its own cadence. Nothing new belongs here; a derivation added today
 *     goes in {@link no.sikt.graphitron.model.run.ModelCapture} beside the facts it reads.
 */
@Deprecated
public final class FactCapture {

    private static final Logger LOG = LoggerFactory.getLogger(FactCapture.class);

    private FactCapture() {}

    /**
     * The derivations left over from the walk, run after the pass that writes what they read.
     *
     * <p>All that survives of a pass that used to capture. Every fact it wrote is written by
     * {@link no.sikt.graphitron.model.run.ModelCapture} now, and what is left reads those facts
     * and derives from them: the classification domain, four hand-written derivations and the
     * registered materialisations. It is called at that pass's tail rather than beside it,
     * because there is nothing here that does not read what the pass wrote.
     *
     * <p>Takes no instant, unlike every gatherer in the pass. Each relation below is emptied and
     * refilled for the graph rather than marked and swept, so there is nothing here a reading's
     * instant would date.
     *
     * @param assembly the corpus as it assembled, for the one derivation that reads an executable
     *                 schema rather than rows; a rejected assembly hands it null and it says so
     */
    public static void derive(DSLContext dsl, GraphIdentity graph, SchemaAssembly assembly) {
        derive(dsl, graph, assembly, refreshLines());
    }

    /**
     * {@link #derive(DSLContext, GraphIdentity, SchemaAssembly)} reporting the
     * materialization refresh to {@code refresh} rather than to this class's log lines.
     *
     * <p>The events are the same either way, {@link #refreshLines} being one rendering of them. It
     * exists because the cadence chosen below is only observable from inside: both cadences leave
     * the same store behind, so the invariant that broke, that the cadence is decided on the
     * register's state and not on the anchor row, is not assertable from the outside at all.
     *
     * @param refresh what the refresh pass reports to, never null; {@link RefreshProgress#none()}
     *                for a caller that wants silence
     */
    public static void derive(DSLContext dsl, GraphIdentity graph, SchemaAssembly assembly,
                              RefreshProgress refresh) {
        Objects.requireNonNull(refresh, "refresh");
        Objects.requireNonNull(assembly, "assembly");
        // Asked before the transaction opens, of the register rather than of store_graph. The
        // condition used to be "this store holds no graph", which is a proxy three unrelated
        // writers of the anchor row defeat, the build path's configuration capture among them;
        // Materializations.analysingCadenceApplies carries what the cadence actually turns on.
        boolean analysingCadence = Materializations.analysingCadenceApplies(dsl);
        dsl.transaction(tx -> {
            DSLContext txDsl = tx.dsl();
            // Everything the pass ahead of this one writes is gone from here: the graph row, the
            // catalog, the corpus and both entry strata, both sets of anchors and the resolution
            // stages, and the catalog's key closure, which the pass runs where it belongs, right
            // after the catalog it closes over. What is left is the derivations nothing else runs.

            // The stage stratum, ahead of the hand-written producers because its rule reads only
            // what the gatherers already wrote and a later step may read its rows. A stage is an
            // INSERT over the view stating its rule; StageOrderGateTest holds the order against
            // each rule's parsed read set and each producer's declared write set.
            FieldColumnScopes.derive(txDsl, graph.name());
            ClassificationDomainCapture.derive(txDsl, graph.name(),
                assembly instanceof SchemaAssembly.Assembled a ? a.schema() : null);
            InputOccurrencePaths.derive(txDsl, graph.name());
            ArgMappingCandidates.derive(txDsl, graph.name());
            TypeBackingRows.derive(txDsl, graph.name());
            AuthoredClaimRejectionRows.derive(txDsl, graph.name());
            // The stages whose rules reach a table one of the producers above writes, so they run
            // after them rather than at the front of the stratum, and before the refresh because
            // the registrations that survive read their rows.
            CarrierDataFields.derive(txDsl, graph.name());
            FieldScopeTables.derive(txDsl, graph.name());
            ArgumentScopeTables.derive(txDsl, graph.name());
            InputFieldResolvingTables.derive(txDsl, graph.name());
            ArgumentReferenceStepTargets.derive(txDsl, graph.name());
            InputFieldReferenceStepTargets.derive(txDsl, graph.name());
            ArgumentColumnScopes.derive(txDsl, graph.name());
            ArgumentColumnMatches.derive(txDsl, graph.name());
            MutationWritePayloads.derive(txDsl, graph.name());
            NodeIdInstructions.derive(txDsl, graph.name());
            NodeIdDecodeHops.derive(txDsl, graph.name());
            NodeIdDecodeHopColumns.derive(txDsl, graph.name());
            NodeIdDecodeColumns.derive(txDsl, graph.name());
            InputFieldColumnMatches.derive(txDsl, graph.name());
            InputFieldFilterRoles.derive(txDsl, graph.name());
            InputFieldCarrierRoles.derive(txDsl, graph.name());
            MutationPayloadRefusals.derive(txDsl, graph.name());
            MutationPayloadColumns.derive(txDsl, graph.name());
            MutationPayloadKeyMemberships.derive(txDsl, graph.name());
            MutationWriteDestinations.derive(txDsl, graph.name());
            // The one producer that used to run alone after the refresh, for a dependency that no
            // longer exists: the view it renders reads the field-site scope, which the refresh was
            // what filled and which the stage above it fills now. Its position here is the read set
            // it always had, met one step earlier.
            UnlowerableOrderingRejectionRows.derive(txDsl, graph.name());
            if (!analysingCadence) {
                Materializations.refresh(txDsl, graph.name(), refresh);
            }
        });
        if (analysingCadence) {
            // The one exception to the paragraph above, and the whole of it: a store no registered
            // target held a row in when this capture began refreshes outside this transaction, one
            // committed transaction per registration, analysing each target as it refills it. Every
            // target on such a store is empty, so the pass inside the transaction plans every
            // statement it issues with no selectivity on anything it reads, which on a consumer-size
            // schema is hours rather than a factor; Materializations.refreshAnalysing carries the
            // measurement, and carries why a commit between two registrations empties nothing that
            // was committed there. Nothing before this point is conditional: the facts, the anchor
            // row and the hand-written derivations are written the same way on both paths.
            Materializations.refreshAnalysing(dsl, graph.name(), refresh);
        }
        // Statistics on what the refresh above just rewrote, so the planner uses the indexes
        // declared beside the materialized targets. Outside the transaction and not inside it,
        // which Materializations.analyse states the reason for: H2's ANALYZE commits, and a commit
        // between this capture's delete and its inserts would publish the emptied partition the
        // one-transaction contract above exists to prevent. After the commit the store is settled,
        // so analysing here is exactly as safe as the dev session's own call and reaches the
        // readers a captured store has, the build path's diagnostics among them. On the analysing
        // path it is the idempotent restatement of what that pass already analysed, kept so that one
        // call states the whole register's statistics on every path out of a capture.
        Materializations.analyse(dsl);
    }

    /**
     * What a refresh says on the way past: two lines per capture at info naming the pass, and the
     * per-registration tier at debug, which {@code mvn -X} turns on. Named once because both refresh
     * cadences a capture can take report the same way, and a cadence reporting differently would
     * make the two paths' logs incomparable on exactly the runs anybody reads them for.
     */
    private static RefreshProgress refreshLines() {
        return RefreshProgress.lines(LOG::info, LOG::debug);
    }

}
