package no.sikt.graphitron.facts;

import graphql.schema.GraphQLFieldDefinition;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The write trigger relation: one row per reachable field coordinate carrying a
 * {@code @mutation} application. Produced once, by {@link WriteFactVisitor}; the directive
 * name has its single lexical home there.
 *
 * <p>The row carries the raw verb token exactly as authored ({@code typeName:}), the
 * {@code multiRow:} flag and the raw {@code table:} argument; validating the verb against the
 * supported set, resolving the write target and building the per-verb payloads stay with the
 * classification-side resolver. The walked fact is the application and its authored surface.
 * Routine-backed writes ({@code @routine} on a mutation root) are deliberately outside this
 * relation: their trigger is the routine chain, not this directive.
 *
 * <p>Rows are labeled with their coordinate but indexed by definition-node identity within the
 * one pre-rewrite assembled schema the gather walked, exactly as
 * {@link PaginationFacts#rowsByDefinition} is.
 */
public record WriteFacts(Map<GraphQLFieldDefinition, Row> rowsByDefinition) {

    public WriteFacts {
        Objects.requireNonNull(rowsByDefinition, "rowsByDefinition");
    }

    /**
     * One coordinate's write application: the raw verb token (absent when the author omitted
     * the argument, a malformed application the resolver rejects), the bulk flag and the raw
     * field-relative write-target table name.
     */
    public record Row(String parentTypeName, String fieldName,
                      Optional<String> verb, boolean multiRow, Optional<String> table) {
        public Row {
            Objects.requireNonNull(parentTypeName, "parentTypeName");
            Objects.requireNonNull(fieldName, "fieldName");
            Objects.requireNonNull(verb, "verb");
            Objects.requireNonNull(table, "table");
        }
    }

    /** The coordinate's write row, or empty when the directive is absent. */
    public Optional<Row> rowFor(GraphQLFieldDefinition fieldDef) {
        return Optional.ofNullable(rowsByDefinition.get(fieldDef));
    }

    /** Every row, for the population pins. */
    public Collection<Row> rows() {
        return rowsByDefinition.values();
    }
}
