package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.run.CapturedGraph;
import no.sikt.graphitron.model.run.GraphitronStore;

import java.util.function.Consumer;

/**
 * A run's graph captured as a goal captures it, for a test that drives the generator end to end.
 *
 * <p>The one way a module downstream of the model has a run captured: it never calls
 * {@link GraphitronStore#captured} itself. A run's lease comes from the {@link FactGraphs}
 * population, which owns the stores tests capture into; a run naming a store directory captures
 * into a store of its own there, and a fixture keeping that store past the run takes it with
 * {@link #own}.
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
            return new Lease(own(ctx), CapturedGraph::close);
        }
        return FactGraphs.population().lend(ctx);
    }

    /**
     * {@code ctx}'s graph captured into a store of the caller's own under the store directory the
     * context names, for a fixture that keeps the store past the run and closes it itself.
     */
    public static CapturedGraph own(RunContext ctx) {
        if (ctx.storeDirectory() == null) {
            throw new IllegalArgumentException("a store of one's own lives in a store directory, "
                + "and this run names none; take a lease with open(ctx) instead");
        }
        return GraphitronStore.captured(ctx);
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
