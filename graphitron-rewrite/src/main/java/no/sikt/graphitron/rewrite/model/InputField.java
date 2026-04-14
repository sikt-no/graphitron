package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;

import java.util.List;

/**
 * Classifies every field in a GraphQL input object type.
 * The sealed hierarchy mirrors the input-field taxonomy, parallel to {@link ChildField} for output fields.
 *
 * <p>Currently only {@code @table}-annotated input types have classified fields; developer-owned
 * input types ({@link GraphitronType.InputType}) have no field-level classification yet.
 */
public sealed interface InputField extends GraphitronField
        permits InputField.ColumnField, InputField.ColumnReferenceField {

    /**
     * A field in a {@code @table}-annotated input type, successfully resolved to a SQL column
     * on the type's own table.
     *
     * <p>The {@link ColumnRef} carries the jOOQ identity of the column: SQL name, Java constant
     * name, and Java class. The GraphQL layer ({@code typeName}, {@code nonNull}, {@code list})
     * describes the shape of the value the caller supplies.
     *
     * <p>If a field's column cannot be resolved at build time the entire containing
     * {@link GraphitronType.TableInputType} is replaced by a
     * {@link GraphitronType.UnclassifiedType}.
     */
    record ColumnField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String typeName,
        boolean nonNull,
        boolean list,
        ColumnRef column
    ) implements InputField {}

    /**
     * A field in a {@code @table}-annotated input type that uses {@code @reference} to reach a
     * column on a joined table.
     *
     * <p>{@code joinPath} is the resolved FK join path from the input type's own table to the
     * terminal table that holds {@code column}. The path is produced by the same reference-path
     * parser as {@link ChildField.ColumnReferenceField}.
     *
     * <p>When generating WHERE predicates (e.g. inside {@link WhereFilter.InputFilter}), the
     * generator must JOIN through {@code joinPath} before applying the column predicate.
     */
    record ColumnReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String typeName,
        boolean nonNull,
        boolean list,
        ColumnRef column,
        List<JoinStep> joinPath
    ) implements InputField {}
}
