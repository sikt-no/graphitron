package no.sikt.graphitron.command;

import java.util.Objects;

/**
 * How a launcher's query composition is invoked: strategy as data on the command, never emitter
 * control flow. Two coordinates whose compositions agree differ in exactly this slot when only
 * their tenancy binding differs, which is the strategy axis being real rather than asserted.
 *
 * <p>The strategy decides the launcher unit's <em>interface</em>, not only its body: the
 * parameter list is a projection of the arm ({@link Direct} takes the one resolved
 * {@code DSLContext}; {@link FannedOverTenants} takes none, its acquisition being plural and
 * internal to the scatter carrier; the batched child arm, when it folds in, takes its keys).
 * The composition fragment itself is shared: one select chain, with the arm deciding whether
 * {@code dsl} is bound by a parameter or by the strategy's per-tenant lambda.
 *
 * <p>Sealed rather than an enum because the fanned arm carries a payload from its first row:
 * the generated scatter carrier's unit ref, so the launcher's emitted references to it are data
 * the edge view can walk rather than a name the renderer re-derives.
 */
public sealed interface Invocation {

    /** The entry point resolves one {@code DSLContext} and calls the launcher once with it. */
    record Direct() implements Invocation {}

    /**
     * The composition runs once per tenant in the request's fan-out domain through the scatter
     * carrier ({@link #carrier}, the generated {@code TenantConnections}), results concatenated
     * in domain order. The launcher hoists every env-derived value onto the dispatch thread and
     * the per-tenant lambda closes over those locals plus its own {@code dsl}, preserving the
     * scatter contract that workers read no shared graphql-java state; the entry point collapses
     * the outcome list through the same carrier.
     */
    record FannedOverTenants(UnitRef carrier) implements Invocation {
        public FannedOverTenants {
            Objects.requireNonNull(carrier, "carrier");
        }
    }
}
