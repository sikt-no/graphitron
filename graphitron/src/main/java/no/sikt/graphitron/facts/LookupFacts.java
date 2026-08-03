package no.sikt.graphitron.facts;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLTypeUtil;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The lookup-trigger relation: one field row per reachable field coordinate carrying a
 * {@code @lookupKey}-annotated argument, one input-field row per reachable input object field
 * carrying the directive, and the type-grain closure of the input-field applications (the
 * input type names that carry the directive on their own fields or transitively through
 * input-object-typed fields). Produced once, by {@link LookupFactVisitor}; the directive name
 * has its single lexical home there.
 *
 * <p>A coordinate's lookup trigger fires when {@code @lookupKey} appears anywhere on its
 * argument surface: directly on an argument (these field rows) or on a member of an argument's
 * input object type, however deeply nested ({@link #triggersFor} joins the argument's type
 * reference against the closed type set). The resulting key mapping stays with the
 * classification-side resolver; the walked fact is the application site.
 *
 * <p>Rows are labeled with their coordinate but indexed by definition-node identity within the
 * one pre-rewrite assembled schema the gather walked, exactly as
 * {@link PaginationFacts#rowsByDefinition} is.
 */
public record LookupFacts(Map<GraphQLFieldDefinition, FieldRow> fieldRows,
                          Map<GraphQLInputObjectField, InputFieldRow> inputFieldRows,
                          Set<String> lookupBearingInputTypes) {

    public LookupFacts {
        Objects.requireNonNull(fieldRows, "fieldRows");
        Objects.requireNonNull(inputFieldRows, "inputFieldRows");
        lookupBearingInputTypes = Set.copyOf(lookupBearingInputTypes);
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

    /** One {@code @lookupKey} application on an input object field. */
    public record InputFieldRow(String inputTypeName, String fieldName) {
        public InputFieldRow {
            Objects.requireNonNull(inputTypeName, "inputTypeName");
            Objects.requireNonNull(fieldName, "fieldName");
        }
    }

    /** The coordinate's direct lookup row, or empty when no argument carries the directive. */
    public Optional<FieldRow> rowFor(GraphQLFieldDefinition fieldDef) {
        return Optional.ofNullable(fieldRows.get(fieldDef));
    }

    /**
     * Whether the coordinate's argument surface applies {@code @lookupKey} anywhere: a directly
     * annotated argument, or an argument whose input object type is in the transitive
     * lookup-bearing closure. The single home of the field-level trigger predicate; the
     * classifier delegates here rather than re-walking the directive.
     */
    public boolean triggersFor(GraphQLFieldDefinition fieldDef) {
        if (fieldRows.containsKey(fieldDef)) {
            return true;
        }
        for (var arg : fieldDef.getArguments()) {
            if (GraphQLTypeUtil.unwrapAll(arg.getType()) instanceof GraphQLNamedType named
                    && lookupBearingInputTypes.contains(named.getName())) {
                return true;
            }
        }
        return false;
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
