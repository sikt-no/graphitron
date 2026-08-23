package no.sikt.graphitron.command;

import graphql.schema.FieldCoordinates;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One generation run's tenancy-acquisition axis: whether the run routes connections per tenant
 * at all, and, when it does, how each covered coordinate acquires its own. A run-grain fact,
 * carried on the command relation the covered coordinates live in rather than copied onto every
 * row, which is the rule {@link CarrierDsl} states for the carrier-routing fact beside it.
 *
 * <p>The axis rides the relation as an overlay index rather than as a slot on each row for the
 * reason the classifier's own binding index does: in a single-tenant build the axis is absent,
 * not uniformly untenanted, so a per-row slot would have to be either nullable or stamped with
 * the run fact on every row. {@link Unrouted} states the absence once and {@link Routed} states
 * both the carrier every acquisition goes through and the per-coordinate arms, so a run cannot
 * hold a mixture of routed and unrouted coordinates.
 *
 * <p>Deliberately no "the acquisition here, or nothing" accessor: a reader forks on the arm, so
 * an absent coordinate under {@link Routed} surfaces as the coverage failure it is instead of
 * reading as a single-tenant run. Routing a tenant-scoped read through the default connection
 * because nothing named the tenant is exactly the leak this axis exists to prevent.
 *
 * <p>Designed for every family whose entry points declare a {@code DSLContext}, not for one:
 * the acquisition shapes are the generated carrier's, and the carrier is one per run.
 */
public sealed interface TenantRouting {

    /**
     * A single-tenant run: no {@code <tenantColumn>} is configured, no generated carrier exists,
     * and every entry point declares the one connection the request context holds.
     */
    record Unrouted() implements TenantRouting {}

    /**
     * A multi-tenant run: every acquisition goes through {@code connections}, the generated
     * scatter-and-acquisition carrier, and {@code byCoordinate} carries one arm per covered
     * coordinate. Totality over a relation's own rows is that relation's invariant to hold, not
     * this record's: what is covered differs per family, and a family that has not migrated onto
     * the seam covers none of it.
     */
    record Routed(UnitRef connections,
                  Map<FieldCoordinates, TenantAcquisition> byCoordinate) implements TenantRouting {

        public Routed {
            Objects.requireNonNull(connections, "connections");
            byCoordinate = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byCoordinate));
        }
    }
}
