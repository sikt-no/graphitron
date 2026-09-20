package no.sikt.graphitron.model.capture;

import no.sikt.graphitron.model.derive.ArgMappingCandidates;
import no.sikt.graphitron.model.derive.AuthoredClaimRejectionRows;
import no.sikt.graphitron.model.derive.ClassificationDomainCapture;
import no.sikt.graphitron.model.derive.InputOccurrencePaths;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.derive.NameMatchedKeys;
import no.sikt.graphitron.model.derive.RefreshProgress;
import no.sikt.graphitron.model.derive.TypeBackingRows;
import no.sikt.graphitron.model.derive.UnlowerableOrderingRejectionRows;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.sink.FactSink;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Objects;


/**
 * The derivations the capture pass leaves behind: the catalog's key closure, the classification
 * domain, four hand-written derivations and the registered materialisations.
 *
 * <p>It captures nothing. Every fact it used to write is written by
 * {@link no.sikt.graphitron.model.run.ModelCapture}, which runs these at its tail because each
 * reads what that pass wrote. The store is the caller's, opened and closed by a mojo or a test.
 */
public final class FactCapture {

    private static final Logger LOG = LoggerFactory.getLogger(FactCapture.class);

    private FactCapture() {}

    /**
     * The derivations left over from the walk, run after the pass that writes what they read.
     *
     * <p>All that survives of a pass that used to capture. Every fact it wrote is written by
     * {@link no.sikt.graphitron.model.run.ModelCapture} now, and what is left reads those facts
     * and derives from them: the catalog's key closure, the classification domain, four hand
     * written derivations and the registered materialisations. It is called at that pass's tail
     * rather than beside it, because there is nothing here that does not read what the pass wrote.
     *
     * @param assembly the corpus as it assembled, for the one derivation that reads an executable
     *                 schema rather than rows; a rejected assembly hands it null and it says so
     */
    public static void derive(DSLContext dsl, GraphIdentity graph, SchemaAssembly assembly,
                              LocalDateTime readAt) {
        derive(dsl, graph, assembly, readAt, refreshLines());
    }

    /**
     * {@link #derive(DSLContext, GraphIdentity, SchemaAssembly, LocalDateTime)} reporting the
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
                              LocalDateTime readAt, RefreshProgress refresh) {
        Objects.requireNonNull(refresh, "refresh");
        Objects.requireNonNull(assembly, "assembly");
        // Asked before the transaction opens, of the register rather than of store_graph. The
        // condition used to be "this store holds no graph", which is a proxy three unrelated
        // writers of the anchor row defeat, the build path's configuration capture among them;
        // Materializations.analysingCadenceApplies carries what the cadence actually turns on.
        boolean analysingCadence = Materializations.analysingCadenceApplies(dsl);
        dsl.transaction(tx -> {
            DSLContext txDsl = tx.dsl();
            var sink = new FactSink(txDsl, graph.name(), readAt);
            // Everything the pass ahead of this one writes is gone from here: the graph row, the
            // catalog, the corpus and both entry strata, both sets of anchors and the resolution
            // stages. It ran them all a second time and overwrote what the pass had just written,
            // which is the duplication this arc removes rather than orders. What is left is the
            // derivations nothing else runs yet.
            NameMatchedKeys.derive(txDsl);
            sink.flush();
            ClassificationDomainCapture.derive(txDsl, graph.name(),
                assembly instanceof SchemaAssembly.Assembled a ? a.schema() : null);
            InputOccurrencePaths.derive(txDsl, graph.name());
            ArgMappingCandidates.derive(txDsl, graph.name());
            TypeBackingRows.derive(txDsl, graph.name());
            AuthoredClaimRejectionRows.derive(txDsl, graph.name());
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
        // The one capture-cadence writer that cannot run beside its siblings above, and the reason
        // is a dependency rather than a preference: the view it renders reads
        // intent_field_scope_table, which the refresh above is what fills, so a call inside the
        // load transaction would render the previous capture's rows. Its own transaction after the
        // refresh, and after the analyse so the read is planned against current statistics, is
        // therefore the earliest point at which it can see what this capture landed. Nothing the
        // build path reads waits on it: the rejection it stores is for the diagnostics surface, and
        // the error stream mints the same value off the view directly, so a reader arriving in the
        // window between the refresh and this write sees the diagnostic missing rather than wrong.
        dsl.transaction(tx -> UnlowerableOrderingRejectionRows.derive(tx.dsl(), graph.name()));
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
