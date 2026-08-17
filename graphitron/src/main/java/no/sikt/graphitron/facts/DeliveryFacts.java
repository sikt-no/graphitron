package no.sikt.graphitron.facts;

import graphql.schema.GraphQLFieldDefinition;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The authored delivery-marker relation: one row per reachable field coordinate carrying a
 * split-forcing marker. Produced once, by {@link DeliveryFactVisitor}; the directive names have
 * their single lexical home there.
 *
 * <p>The row carries the two markers separately because their forcing scopes differ: the
 * table-backed child arm reads the union ({@link Row#forcesSplitDelivery}), while the
 * {@code @pivot} batching gate, the nesting-projection deferral and the two redundancy
 * diagnostics (a record-backed parent, a discriminated interface child) read the
 * {@code @splitQuery} half alone ({@link Row#splitQuery}; a fanned pivot has no scatter emission,
 * and {@code @tenantFanOut} on other shapes is the tenant fold's sweep to reject). Exposing the
 * structured pair keeps both reads on one gathered fact instead of two directive probes.
 *
 * <p>Rows are labeled with their coordinate but indexed by definition-node identity within the
 * one pre-rewrite assembled schema the gather walked, exactly as
 * {@link PaginationFacts#rowsByDefinition} is.
 */
public record DeliveryFacts(Map<GraphQLFieldDefinition, Row> rowsByDefinition) {

    public DeliveryFacts {
        Objects.requireNonNull(rowsByDefinition, "rowsByDefinition");
    }

    /** One coordinate's authored markers; minted only when at least one marker is present. */
    public record Row(String parentTypeName, String fieldName, boolean splitQuery, boolean tenantFanOut) {
        public Row {
            Objects.requireNonNull(parentTypeName, "parentTypeName");
            Objects.requireNonNull(fieldName, "fieldName");
            if (!splitQuery && !tenantFanOut) {
                throw new IllegalArgumentException(
                    "a delivery-marker row exists only when a marker is present; markerless "
                    + "coordinates are the relation's absence, not an all-false row");
            }
        }

        /** The delivery-forcing union the table-backed child arm reads. */
        public boolean forcesSplitDelivery() {
            return splitQuery || tenantFanOut;
        }
    }

    /** The coordinate's marker row, or empty when neither marker is applied. */
    public Optional<Row> rowFor(GraphQLFieldDefinition fieldDef) {
        return Optional.ofNullable(rowsByDefinition.get(fieldDef));
    }

    /** Whether the coordinate carries the delivery-forcing marker union. */
    public boolean forcesSplitDelivery(GraphQLFieldDefinition fieldDef) {
        return rowFor(fieldDef).map(Row::forcesSplitDelivery).orElse(false);
    }

    /**
     * Whether the coordinate carries {@code @splitQuery} (the half the {@code @pivot} gate, the
     * nesting deferral and the two redundancy diagnostics read).
     */
    public boolean splitQuery(GraphQLFieldDefinition fieldDef) {
        return rowFor(fieldDef).map(Row::splitQuery).orElse(false);
    }

    /** Every row, for the population pins. */
    public Collection<Row> rows() {
        return rowsByDefinition.values();
    }
}
