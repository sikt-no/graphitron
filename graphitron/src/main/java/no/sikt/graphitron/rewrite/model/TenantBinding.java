package no.sikt.graphitron.rewrite.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * The field resolves by node id; the tenant is a decoded-column position of the batch key.
     * Node fields are polymorphic, so the position is carried per candidate node type
     * ({@code positionByTypeName}, in candidate declaration order): each id in a batch carries
     * its own tenant, read at its type's position.
     */
    record NodeIdBound(Map<String, Integer> positionByTypeName) implements TenantBinding {
        public NodeIdBound {
            positionByTypeName = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(positionByTypeName));
        }
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
}
