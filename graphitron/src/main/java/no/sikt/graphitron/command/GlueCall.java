package no.sikt.graphitron.command;

import java.util.Objects;

/**
 * A reference to a coordinate's condition glue method as a WHERE-clause contribution: the
 * {@code <Parent>Conditions.<field>Condition(<alias>, <argsMap>[, env][, dsl])} call a consumer
 * composes into its query. {@code takesEnv} is the glue signature's env-appending fork,
 * resolved from the condition row's own {@code readsRequestContext()} at produce time, and
 * {@code takesDsl} its {@code dsl}-appending fork, resolved from {@code readsSession()}: a
 * session-reading glue reads the handle off the executing connection, so the call site passes the
 * statement's {@code dsl}. Carrying the pre-resolved booleans here keeps the renderer free of the
 * model predicates the folds derive from, and single-sources the facts on the condition relation
 * (the projection producer copies the row's answer, it never recomputes it from filters).
 *
 * <p>Shared command vocabulary: the projection command's correlated subqueries reference glue
 * through this record, and the launcher command's WHERE slot takes the same shape.
 */
public record GlueCall(UnitMethodRef method, boolean takesEnv, boolean takesDsl) {

    public GlueCall {
        Objects.requireNonNull(method, "method");
    }
}
