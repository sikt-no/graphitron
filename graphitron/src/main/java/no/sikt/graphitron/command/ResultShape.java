package no.sikt.graphitron.command;

import java.util.Objects;

/**
 * The payload a launcher returns and its thin entry point wraps: one fact with two consumers,
 * derived once by the producer from the coordinate's cardinality and pagination so the renderer
 * reads a shape instead of deriving one. A single {@code org.jooq.Record} fetched with
 * {@code fetchOne()}, a {@code Result<Record>} fetched with {@code fetch()}, or the connection
 * carrier built over the seek/limit page query.
 *
 * <p>The ordering rides the shape rather than the command, because the two co-vary: a
 * single-record launcher is unordered by construction (the model's ordering contract gives
 * single-value fields no spec), so a top-level ordering slot would make the illegal pair
 * representable and pay a runtime check for a correlation the type can carry.
 *
 * <p>Value-adjacent to {@link Arity} but not the same fact: this is a launcher's return type
 * (and {@link Connection} is not an arity at all), where {@code Arity} is a multiset
 * contribution's unwrap decision; the two must not be fused.
 */
public sealed interface ResultShape {

    /** One record or null: the {@code fetchOne()} shape, unordered by construction. */
    record SingleRecord() implements ResultShape {}

    /**
     * A record list: the {@code fetch()} shape under {@link #ordering}. The slot is absent for
     * two populations: the schema-free unit-tier assemblies, and classified root {@code @routine}
     * chains, whose lists are unordered by classification (the chain defers the {@code @orderBy}
     * surface, and the deterministic-order validation keys on a capability the routine leaf does
     * not implement, a gap recorded on the launcher item's hand-off). The renderer renders the
     * absent arm with no ORDER BY clause, the same SQL an empty sort list produces.
     */
    record RecordList(Ordering ordering) implements ResultShape {}

    /**
     * The connection shape: the seek/limit page query, wrapped in the generated carrier the lazy
     * resolvers (cursor encode, {@code totalCount}) read. {@link #ordering} is total here
     * (pagination requires ordering, validator-enforced) and serves both views the page request
     * needs, the sort fields and the cursor columns; {@link #defaultPageSize} is the one
     * build-time pagination fact the composition consumes (the four argument names are fixed by
     * the slot, so the model never holds them). {@link #helper} and {@link #carrier} are the
     * generated connection runtime's units ({@code ConnectionHelper}, {@code ConnectionResult}),
     * copied off the naming vocabulary by the producer so the launcher's edges to them are data,
     * the same handshake shape the WHERE slot uses against the condition relation.
     *
     * <p>{@link #facets} is the faceted carrier's plan (the base and per-facet fragment refs
     * plus decode specs), absent exactly when the coordinate carries no facets; the carrier
     * construction forks on that absence, matching the runtime's two constructor shapes.
     */
    record Connection(Ordering ordering, int defaultPageSize, UnitRef helper, UnitRef carrier,
            FacetPlan facets)
            implements ResultShape {
        public Connection {
            Objects.requireNonNull(ordering, "a connection composition is ordered by construction");
            Objects.requireNonNull(helper, "helper");
            Objects.requireNonNull(carrier, "carrier");
        }
    }
}
