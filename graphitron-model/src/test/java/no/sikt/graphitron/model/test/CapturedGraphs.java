package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.run.CapturedGraph;
import no.sikt.graphitron.model.run.GraphitronStore;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A generator run over a schema still inline, for a test that drives the generator end to end.
 *
 * <p>Inline SDL is deprecated: a run over a fact document reads the document's graph from
 * {@link FactGraphs} and hands its handle and schema to the generator. Until a run's schema is a
 * document, this captures it as a goal captures the run's context, into a store of the population,
 * and reads it there. A module downstream of the model never calls {@link GraphitronStore#captured}
 * itself. A run naming a store directory keeps a store of its own there, taken with {@link #own}.
 *
 * <pre>{@code
 * CapturedGraphs.read(ctx, run ->
 *     new GraphQLRewriteGenerator(ctx, run.handle(), run.schema()).validate());
 * }</pre>
 */
public final class CapturedGraphs {

    private CapturedGraphs() {}

    /** {@code body} over {@code ctx}'s graph, captured into the population and read in one transaction. */
    public static <T> T read(RunContext ctx, Function<FactGraphs.RunGraph, T> body) {
        return FactGraphs.population().readRun(ctx, body);
    }

    /** {@link #read} for a body that returns nothing. */
    public static void run(RunContext ctx, Consumer<FactGraphs.RunGraph> body) {
        read(ctx, graph -> {
            body.accept(graph);
            return null;
        });
    }

    /**
     * {@code ctx}'s graph captured into a store of the caller's own under the store directory the
     * context names, for a fixture whose subject is that store, which keeps it past the run and
     * closes it itself.
     */
    public static CapturedGraph own(RunContext ctx) {
        if (ctx.storeDirectory() == null) {
            throw new IllegalArgumentException("a store of one's own lives in a store directory, "
                + "and this run names none; read it from the population with read(ctx, ...) instead");
        }
        return GraphitronStore.captured(ctx);
    }
}
