package no.sikt.graphitron.facts;

import graphql.schema.GraphQLFieldDefinition;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The service-call trigger relation: one row per reachable field coordinate carrying a
 * {@code @service} application. Produced once, by {@link ServiceFactVisitor}; the directive
 * name has its single lexical home there.
 *
 * <p>Parsing the structured reference, reflecting the consumer's Java surface and binding
 * arguments stay with the classification-side resolver; the walked fact is the application
 * site.
 *
 * <p>Rows are labeled with their coordinate but indexed by definition-node identity within the
 * one pre-rewrite assembled schema the gather walked, exactly as
 * {@link PaginationFacts#rowsByDefinition} is.
 */
public record ServiceFacts(Map<GraphQLFieldDefinition, Row> rowsByDefinition) {

    public ServiceFacts {
        Objects.requireNonNull(rowsByDefinition, "rowsByDefinition");
    }

    /**
     * One coordinate's service application. Presence-grain: the directive's structured
     * {@code service:} object argument (class reference, method, argument mapping) is the
     * resolver's payload, not a walkable scalar surface, so the walked fact is the application
     * site alone.
     */
    public record Row(String parentTypeName, String fieldName) {
        public Row {
            Objects.requireNonNull(parentTypeName, "parentTypeName");
            Objects.requireNonNull(fieldName, "fieldName");
        }
    }

    /** The coordinate's service row, or empty when the directive is absent. */
    public Optional<Row> rowFor(GraphQLFieldDefinition fieldDef) {
        return Optional.ofNullable(rowsByDefinition.get(fieldDef));
    }

    /** Every row, for the population pins. */
    public Collection<Row> rows() {
        return rowsByDefinition.values();
    }
}
