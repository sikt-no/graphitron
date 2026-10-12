package no.sikt.graphitron.command;

import java.util.List;
import java.util.Objects;

/**
 * The launcher's tenancy axis: whether the composition runs once against one resolved
 * {@code DSLContext} or once per tenant in the request's fan-out domain. Independent of the
 * delivery axis ({@link Invocation}) by measurement, not aesthetics: the fanned batched child
 * is a populated cell in the pipeline fixtures ({@code TenantFanOutFetcherPipelineTest}'s
 * batched-form fanned child), so fusing the two axes into one permit set would make it
 * unrepresentable or mint the cross-product arm.
 *
 * <p>Sealed rather than an enum because the fanned arm carries a payload from its first row:
 * the generated scatter carrier's unit ref, so the launcher's emitted references to it are data
 * the edge view can walk rather than a name the renderer re-derives.
 */
public sealed interface TenantStrategy {

    /** One tenant: the composition binds one {@code DSLContext} and runs once. */
    record Single() implements TenantStrategy {}

    /**
     * The composition runs once per tenant in the request's fan-out domain through the scatter
     * carrier ({@link #carrier}, the generated {@code TenantConnections}), results concatenated
     * in domain order. The launcher hoists every env-derived value onto the dispatch thread and
     * the per-tenant lambda closes over those locals plus its own {@code dsl}, preserving the
     * scatter contract that workers read no shared graphql-java state; the entry point collapses
     * the outcome list through the same carrier.
     *
     * <p>{@link #narrowing} names the field's optional tenant slots, in binding order: the
     * launcher reads each one's value on the dispatch thread and hands it to the carrier, which
     * restricts the domain to the tenants the given values name. Empty for a plain fan-out, where
     * the domain is the request's whole set. Names rather than the slots themselves, since how a
     * slot is read is classification-side emission the shell renders from the binding.
     */
    record Fanned(UnitRef carrier, List<String> narrowing) implements TenantStrategy {
        public Fanned {
            Objects.requireNonNull(carrier, "carrier");
            narrowing = List.copyOf(narrowing);
        }
    }
}
