package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.run.CapturedGraph;
import no.sikt.graphitron.model.run.GraphitronStore;

import java.util.function.Consumer;

/**
 * A run's graph captured as a goal captures it, for a test that drives the generator end to end.
 *
 * <p>The one way a module downstream of the model has a run captured: it never calls
 * {@link GraphitronStore#captured} itself. A run naming a store directory gets a store of its own
 * there, since it asked for one that outlives it; any other run's store comes from the
 * {@link FactGraphs} population, which owns the stores tests capture into.
 *
 * <pre>{@code
 * try (var run = CapturedGraphs.open(ctx)) {
 *     new GraphQLRewriteGenerator(ctx, run.graph()).validate();
 * }
 * }</pre>
 */
public final class CapturedGraphs {

    private CapturedGraphs() {}

    /** {@code ctx}'s graph captured, lent until the lease is closed. */
    public static Lease open(RunContext ctx) {
        if (ctx.storeDirectory() != null) {
            return new Lease(GraphitronStore.captured(ctx), CapturedGraph::close);
        }
        return FactGraphs.lendRun(ctx);
    }

    /** A captured graph on loan: closing it gives its store back to whoever lent it. */
    public static final class Lease implements AutoCloseable {

        private final CapturedGraph graph;
        private final Consumer<CapturedGraph> release;

        Lease(CapturedGraph graph, Consumer<CapturedGraph> release) {
            this.graph = graph;
            this.release = release;
        }

        /** The graph, as the generator takes it. */
        public CapturedGraph graph() {
            return graph;
        }

        @Override
        public void close() {
            release.accept(graph);
        }
    }
}
