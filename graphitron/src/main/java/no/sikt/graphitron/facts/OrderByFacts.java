package no.sikt.graphitron.facts;

import graphql.schema.GraphQLFieldDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The authored ordering-trigger relation: one row per reachable field coordinate carrying an
 * {@code @orderBy}-annotated argument or a field-level {@code @defaultOrder} application.
 * Produced once, by {@link OrderByFactVisitor}; both directive names have their single lexical
 * home there.
 *
 * <p>This is the authored half of the ordering population. The resolved {@code OrderBySpec}
 * behind an orderBy member also admits a catalog-derived primary-key fallback (minted where
 * pagination demands a stable order with no authored one), which no SDL-grain visitor can see;
 * the resolver that joins this row with the catalog owns that fallback.
 *
 * <p>Rows are labeled with their coordinate but indexed by definition-node identity within the
 * one pre-rewrite assembled schema the gather walked, exactly as
 * {@link PaginationFacts#rowsByDefinition} is.
 */
public record OrderByFacts(Map<GraphQLFieldDefinition, Row> rowsByDefinition) {

    public OrderByFacts {
        Objects.requireNonNull(rowsByDefinition, "rowsByDefinition");
    }

    /**
     * One coordinate's authored ordering surface: the {@code @orderBy}-annotated argument names
     * in SDL declaration order, and the {@code @defaultOrder} presence flag. A row exists
     * exactly when at least one population is non-empty.
     */
    public record Row(String parentTypeName, String fieldName,
                      List<String> orderByArgs, boolean defaultOrder) {
        public Row {
            Objects.requireNonNull(parentTypeName, "parentTypeName");
            Objects.requireNonNull(fieldName, "fieldName");
            orderByArgs = List.copyOf(orderByArgs);
            if (orderByArgs.isEmpty() && !defaultOrder) {
                throw new IllegalArgumentException(
                    "an ordering row exists only when a population is non-empty; coordinate "
                    + parentTypeName + "." + fieldName + " has neither an orderBy argument nor"
                    + " a defaultOrder application");
            }
        }
    }

    /** The coordinate's authored ordering row, or empty when no application touches it. */
    public Optional<Row> rowFor(GraphQLFieldDefinition fieldDef) {
        return Optional.ofNullable(rowsByDefinition.get(fieldDef));
    }

    /** Every row, for the population pins. */
    public Collection<Row> rows() {
        return rowsByDefinition.values();
    }
}
