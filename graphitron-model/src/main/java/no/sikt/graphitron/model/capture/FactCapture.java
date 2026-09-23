package no.sikt.graphitron.model.capture;

import no.sikt.graphitron.model.derive.DerivationStratum;
import no.sikt.graphitron.model.derive.StageProgress;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;


/**
 * The derivations the capture pass leaves behind: the graphitron gatherer's derivation stratum, run
 * after every gatherer has flushed because every step reads what they wrote.
 *
 * <p>It captures nothing. Every fact it used to write is written by
 * {@link no.sikt.graphitron.model.run.ModelCapture}, which runs this at its tail. The steps, their
 * order and the two cadences they run on are {@link DerivationStratum}'s; what is left here is the
 * entry point and the log lines.
 *
 * @deprecated the last of the walk. The stratum it runs belongs to the graphitron gatherer, and
 *     this class goes when {@link no.sikt.graphitron.model.run.ModelCapture} calls
 *     {@link DerivationStratum} itself. Nothing new belongs here.
 */
@Deprecated
public final class FactCapture {

    private static final Logger LOG = LoggerFactory.getLogger(FactCapture.class);

    private FactCapture() {}

    /**
     * The derivation stratum for one graph, reporting to this class's log lines.
     *
     * <p>Takes no instant, unlike every gatherer in the pass. Each relation the stratum writes is
     * emptied and refilled for the graph rather than marked and swept, so there is nothing here a
     * reading's instant would date.
     *
     * @param assembly the corpus as it assembled, for the one step that reads an executable schema
     *                 rather than rows; a rejected assembly hands it null and it says so
     */
    public static void derive(DSLContext dsl, GraphIdentity graph, SchemaAssembly assembly) {
        derive(dsl, graph, assembly, stageLines());
    }

    /**
     * {@link #derive(DSLContext, GraphIdentity, SchemaAssembly)} reporting to {@code progress}
     * rather than to this class's log lines.
     *
     * <p>The events are the same either way, {@link #stageLines} being one rendering of them. It
     * exists because which cadence the stratum took is only observable from inside: both leave the
     * same rows behind.
     *
     * @param progress what the stratum reports to, never null; {@link StageProgress#none()} for a
     *                 caller that wants silence
     */
    public static void derive(DSLContext dsl, GraphIdentity graph, SchemaAssembly assembly,
                              StageProgress progress) {
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(assembly, "assembly");
        DerivationStratum.run(dsl, graph.name(),
            assembly instanceof SchemaAssembly.Assembled a ? a.schema() : null, progress);
    }

    /**
     * What the stratum says on the way past: two lines per capture at info naming the pass, and the
     * per-stage tier at debug, which {@code mvn -X} turns on. Named once because both cadences report
     * the same way, and a cadence reporting differently would make the two paths' logs incomparable
     * on exactly the runs anybody reads them for.
     */
    private static StageProgress stageLines() {
        return StageProgress.lines(LOG::info, LOG::debug);
    }
}
