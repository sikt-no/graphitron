package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * The per-field tenant-binding axis: where a field's divined tenant key comes from, decided
 * once at the parse boundary from the column mappings the schema already carries. An optional
 * overlay sibling to the {@code source()} / {@code operation()} / {@code target()} axes,
 * computed only when {@code <tenantColumn>} is configured; in single-tenant builds the axis is
 * absent (an empty index), not "everything {@link Untenanted}".
 *
 * <p>{@link NodeIdBound} and {@link EntityRepBound} form the per-row family: a single batch
 * spans tenants, so their consumers partition per tenant rather than hand one value down. The
 * two arms share the "positional slot in a decoded key" shape deliberately: node ids and
 * federation representations both decode at the DataFetcher boundary, and the model carries
 * the decoded position, never a reference into the raw id string or representation map (wire
 * format stays a boundary concern).
 *
 * <p>A field reaching a tenant-scoped table with no binding in scope classifies to no arm at
 * all; it is the typed {@code noTenantBinding} rejection, because routing tenant data through
 * a default connection when nothing named the tenant is exactly the cross-tenant leak this
 * axis exists to prevent.
 */
public sealed interface TenantBinding {

    /**
     * One argument or input-object slot whose column mapping lands on the tenant column.
     * {@code slotName} is the GraphQL argument or input-field name; {@code column} is the
     * resolved tenant column the slot binds.
     */
    record BoundSlot(String slotName, ColumnRef column) {}

    /**
     * Argument / input-object fields whose column mappings resolve to tenant columns. Carries
     * every co-binding; the first is the documented-precedence primary (argument declaration
     * order), and the emitter guards at runtime that all divined values agree.
     */
    record ArgumentBound(List<BoundSlot> bindings) implements TenantBinding {
        public ArgumentBound {
            if (bindings.isEmpty()) {
                throw new IllegalArgumentException("ArgumentBound requires at least one bound slot");
            }
            bindings = List.copyOf(bindings);
        }

        /** The documented-precedence primary: the first bound slot in declaration order. */
        public BoundSlot primary() {
            return bindings.get(0);
        }
    }

    /**
     * The field resolves by node id: each id in a batch carries its own tenant, and the batch
     * partitions per decoded tenant at the dispatch surface. The arm is the verdict alone; the
     * per-type decoded positions the dispatcher reads live on the entity-side facts
     * ({@link EntityRepBound}, keyed by type in the binding index), because node dispatch
     * synthesises representations and resolves through the same surface.
     */
    record NodeIdBound() implements TenantBinding {
        public static final NodeIdBound INSTANCE = new NodeIdBound();
    }

    /**
     * The field resolves {@code _entities} representations; the tenant is a decoded-column
     * position of the batch key, carried per resolvable key alternative
     * ({@code alternativeIndex} is the alternative's position in
     * {@link EntityResolution#alternatives()}). Each representation carries its own tenant.
     */
    record EntityRepBound(List<AlternativeSlot> alternatives) implements TenantBinding {
        public EntityRepBound {
            if (alternatives.isEmpty()) {
                throw new IllegalArgumentException("EntityRepBound requires at least one alternative slot");
            }
            alternatives = List.copyOf(alternatives);
        }

        /** The tenant column's position within one key alternative's decoded column tuple. */
        public record AlternativeSlot(int alternativeIndex, int decodedPosition) {}
    }

    /**
     * A child field inherits the tenant divined at its binding ancestor. Within any execution
     * context made tenant-homogeneous by per-tenant partitioning, inheritance is a value
     * hand-down, not a per-row re-read; {@code parentTypeName} names the parent type whose
     * (tenant-homogeneous) execution context hands the value down. A type can be reached
     * through several bound ancestors, so the model records the field's own parent rather
     * than electing one ancestor coordinate; the runtime carrier reads the value off the
     * execution context, which is per-path.
     */
    record Inherited(String parentTypeName) implements TenantBinding {}

    /** The field touches only global tables; it runs on the default DataSource. */
    record Untenanted() implements TenantBinding {
        public static final Untenanted INSTANCE = new Untenanted();
    }

    /**
     * The deliberate no-binding arm: the field carries {@code @tenantFanOut}, so its query runs
     * once per tenant in the request's fan-out domain and the results union. The arm is the
     * verdict alone; <em>what</em> it fans over is a request-level fact (the factory-supplied
     * tenant collection intersected with the configured tenant map), so there is nothing for the
     * model to carry. Children below a fanned field inherit each unioned row's tenant through
     * per-element {@code localContext} stamping and classify {@link Inherited}.
     */
    record FanOut() implements TenantBinding {
        public static final FanOut INSTANCE = new FanOut();
    }
}
