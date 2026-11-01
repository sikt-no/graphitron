package no.sikt.graphitron.facts;

import graphql.schema.GraphQLFieldDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The lookup-trigger relation: one field row per reachable field coordinate carrying a
 * {@code @lookupKey}-annotated argument. Produced once, by {@link LookupFactVisitor}; the
 * directive name has its single lexical home there. The directive is declared on arguments only,
 * so the argument surface is the whole of the trigger. The resulting key mapping stays with the
 * classification-side resolver; the walked fact is the application site.
 *
 * <p>Rows are labeled with their coordinate but indexed by definition-node identity within the
 * one pre-rewrite assembled schema the gather walked, exactly as
 * {@link PaginationFacts#rowsByDefinition} is.
 */
public record LookupFacts(Map<GraphQLFieldDefinition, FieldRow> fieldRows) {

    public LookupFacts {
        Objects.requireNonNull(fieldRows, "fieldRows");
    }

    /** One coordinate's directly-annotated lookup arguments, in SDL declaration order. */
    public record FieldRow(String parentTypeName, String fieldName, List<String> lookupArgs) {
        public FieldRow {
            Objects.requireNonNull(parentTypeName, "parentTypeName");
            Objects.requireNonNull(fieldName, "fieldName");
            lookupArgs = List.copyOf(lookupArgs);
            if (lookupArgs.isEmpty()) {
                throw new IllegalArgumentException(
                    "a lookup field row exists only when at least one argument carries the"
                    + " directive; coordinate " + parentTypeName + "." + fieldName + " has none");
            }
        }
    }

    /** The coordinate's direct lookup row, or empty when no argument carries the directive. */
    public Optional<FieldRow> rowFor(GraphQLFieldDefinition fieldDef) {
        return Optional.ofNullable(fieldRows.get(fieldDef));
    }

    /**
     * Whether an argument of the coordinate carries {@code @lookupKey}. The single home of the
     * field-level trigger predicate; the classifier delegates here rather than re-walking the
     * directive.
     */
    public boolean triggersFor(GraphQLFieldDefinition fieldDef) {
        return fieldRows.containsKey(fieldDef);
    }

    /** Every field row, for the population pins. */
    public Collection<FieldRow> rows() {
        return fieldRows.values();
    }
}
