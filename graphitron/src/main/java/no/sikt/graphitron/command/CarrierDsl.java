package no.sikt.graphitron.command;

/**
 * How generated connection carriers acquire the {@code DSLContext} their lazy resolvers
 * ({@code totalCount}, the facet aggregate) run against: a per-run fact the plan derives once
 * from the configured tenant scopes, never a per-coordinate one (copying it onto every row would
 * be a derived fact maintained apart from its source). Rides the launcher relation, the family
 * view that renders carriers; the launcher renderer is total over its command plus this run
 * fact.
 */
public enum CarrierDsl {
    /** Single-tenant builds: the carrier acquires through the environment at resolution time. */
    ENV_ACQUIRED,
    /**
     * Multi-tenant builds: the routed {@code DSLContext} rides the carrier, so the lazy
     * resolvers aggregate against the same source the page rows came from.
     */
    ROUTED
}
