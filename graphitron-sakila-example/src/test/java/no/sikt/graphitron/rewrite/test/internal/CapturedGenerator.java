package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;

import java.util.function.Function;

/**
 * A generator over a store this call captured into, for a test in the example module.
 *
 * <p>The generator opens no store and captures nothing. A run is two steps and its owner performs
 * both: whoever holds the store captures the model into it and then invokes the generator on it,
 * which is what the build mojos do and therefore what a test asserting on generated output has to
 * do as well. A generator handed a store nobody captured into emits from an empty partition, and
 * the failure reads as a generator defect rather than as a missing step.
 *
 * <p>Here rather than in each test class because three of them need it and the shape is the run's
 * rather than any one case's.
 */
public final class CapturedGenerator {

    private CapturedGenerator() {}

    /**
     * Captures {@code ctx}'s model, hands the generator the store, and returns what {@code pass}
     * made of it. The store closes when the pass returns, so a caller wanting to read rows back
     * does that inside the pass.
     */
    public static <T> T with(RunContext ctx, Function<GraphQLRewriteGenerator, T> pass) {
        try (var store = GraphitronStore.captured(ctx)) {
            return pass.apply(
                new GraphQLRewriteGenerator(ctx, new StoreHandle(store.dsl(), ctx.graphName())));
        }
    }

    /** {@link #with} for the common pass, which is to emit. */
    public static void generate(RunContext ctx) {
        with(ctx, generator -> {
            generator.generate();
            return null;
        });
    }
}
