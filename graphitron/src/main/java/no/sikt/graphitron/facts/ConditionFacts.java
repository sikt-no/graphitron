package no.sikt.graphitron.facts;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The authored condition-trigger relation: one field row per reachable field coordinate
 * carrying a {@code @condition} application on the field itself or on one of its arguments,
 * and one input-field row per reachable input object field carrying one. Produced once, by
 * {@link ConditionFactVisitor}; the directive name has its single lexical home there.
 *
 * <p>This is the <em>authored</em> half of the condition trigger's population. The full
 * population behind a coordinate's condition members is union-then-suppress: authored
 * applications (these rows, including the ones that arrive through argument-reachable input
 * types) plus the live generated rows riding the reference facts, with {@code override:}
 * suppressing the consumed generated subtree. That resolution is applied where the catalog and
 * the reference facts live; these rows carry the walked trigger surface it starts from.
 *
 * <p>Rows are labeled with their coordinate but indexed by definition-node identity within the
 * one pre-rewrite assembled schema the gather walked, exactly as
 * {@link PaginationFacts#rowsByDefinition} is.
 */
public record ConditionFacts(Map<GraphQLFieldDefinition, FieldRow> fieldRows,
                             Map<GraphQLInputObjectField, InputFieldRow> inputFieldRows) {

    public ConditionFacts {
        Objects.requireNonNull(fieldRows, "fieldRows");
        Objects.requireNonNull(inputFieldRows, "inputFieldRows");
    }

    /** One {@code @condition} application on an argument: the argument's name and its override flag. */
    public record ArgSite(String argName, boolean override) {
        public ArgSite {
            Objects.requireNonNull(argName, "argName");
        }
    }

    /**
     * One field coordinate's authored condition surface. A row exists exactly when the field
     * itself carries the directive or at least one argument does.
     */
    public record FieldRow(String parentTypeName, String fieldName,
                           boolean onField, boolean fieldOverride, List<ArgSite> argSites) {
        public FieldRow {
            Objects.requireNonNull(parentTypeName, "parentTypeName");
            Objects.requireNonNull(fieldName, "fieldName");
            argSites = List.copyOf(argSites);
            if (!onField && argSites.isEmpty()) {
                throw new IllegalArgumentException(
                    "a condition field row exists only when the surface is non-empty; coordinate "
                    + parentTypeName + "." + fieldName + " has neither a field-level application"
                    + " nor an argument-level one");
            }
            if (!onField && fieldOverride) {
                throw new IllegalArgumentException(
                    "fieldOverride without a field-level application on "
                    + parentTypeName + "." + fieldName);
            }
        }
    }

    /** One {@code @condition} application on an input object field. */
    public record InputFieldRow(String inputTypeName, String fieldName, boolean override) {
        public InputFieldRow {
            Objects.requireNonNull(inputTypeName, "inputTypeName");
            Objects.requireNonNull(fieldName, "fieldName");
        }
    }

    /** The coordinate's authored condition row, or empty when no application touches it. */
    public Optional<FieldRow> rowFor(GraphQLFieldDefinition fieldDef) {
        return Optional.ofNullable(fieldRows.get(fieldDef));
    }

    /** Every field row, for the population pins. */
    public Collection<FieldRow> rows() {
        return fieldRows.values();
    }

    /** Every input-field row, for the population pins. */
    public Collection<InputFieldRow> inputRows() {
        return inputFieldRows.values();
    }
}
