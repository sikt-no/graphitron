package no.sikt.graphitron.model.diagnostics;

import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.sink.Progress;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Objects;

/**
 * A round's report in the store: the walk's rejections, the lint findings and the advisories, all
 * of one reading, with that reading's progress in {@code store_graph_progress}.
 *
 * <p>The rows carry the line numbers of the file version the round read, so they are true of that
 * version only. A round that reports replaces them and completes; a round that fails before it can
 * report withholds them, sweeping the rows rather than leaving another version's findings on lines
 * that have since moved, and leaves its progress started and not completed, which is how a reader
 * tells a clean report from a withheld one.
 */
public final class ReportFacts {

    /** This writer's row in {@code store_graph_progress}. */
    public static final String GATHERER = "report";

    private final DSLContext dsl;
    private final GraphIdentity graph;
    private final RejectionFacts rejections;
    private final BuildWarningFacts warnings;

    public ReportFacts(DSLContext dsl, GraphIdentity graph) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.rejections = new RejectionFacts(dsl, graph);
        this.warnings = new BuildWarningFacts(dsl, graph);
    }

    /** Replaces the report with what a round found, and completes. */
    public void write(List<ValidationError> walkErrors, List<BuildWarning> buildWarnings) {
        Progress.started(dsl, graph.name(), GATHERER);
        rejections.write(walkErrors);
        warnings.write(buildWarnings);
        Progress.completed(dsl, graph.name(), GATHERER);
    }

    /** Sweeps the report for a round that failed before it could report, and does not complete. */
    public void withhold() {
        Progress.started(dsl, graph.name(), GATHERER);
        rejections.write(List.of());
        warnings.write(List.of());
    }
}
