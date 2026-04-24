package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ArgConditionRef;

import java.util.List;
import java.util.Optional;

/**
 * Classifies every field in a GraphQL input object type.
 * The sealed hierarchy mirrors the input-field taxonomy, parallel to {@link ChildField} for output fields.
 *
 * <p>{@link ColumnField}, {@link ColumnReferenceField}, and {@link NestingField} carry an optional
 * {@code condition} — an {@link ArgConditionRef} built from a {@code @condition} directive on the
 * input field definition. When present, the condition method fires as an additional WHERE predicate
 * alongside (or instead of, when {@code override: true}) the auto-generated column predicate.
 *
 * <p>{@link PlatformIdField} intentionally omits {@code condition} — see
 * {@code docs/argument-resolution.md} Out of Scope.
 */
public sealed interface InputField extends GraphitronField
        permits InputField.ColumnField, InputField.ColumnReferenceField,
                InputField.NodeIdField, InputField.PlatformIdField, InputField.NestingField {

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
        ColumnRef column,
        Optional<ArgConditionRef> condition
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
        List<JoinStep> joinPath,
        Optional<ArgConditionRef> condition
    ) implements InputField {}

    /**
     * A field in a {@code @table}-annotated input type whose GraphQL type is scalar {@code ID}
     * and whose backing table is a {@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType}.
     *
     * <p>Classified on both the synthesized route (table has {@code __NODE_TYPE_ID} /
     * {@code __NODE_KEY_COLUMNS} constants) and the declared route (field carries {@code @nodeId}).
     * Both paths produce the same variant carrying {@code (nodeTypeId, nodeKeyColumns)}.
     *
     * <p>The generator decodes the base64 composite ID and binds each unpacked value to its
     * own-table column via {@code NodeIdStrategy.unpackIdValues} / {@code hasIds} / {@code hasId}.
     * No {@link ColumnRef} is carried — the columns are in {@code nodeKeyColumns}.
     * A {@code list} field is intentionally omitted — the classifier guarantees scalar.
     */
    record NodeIdField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String nodeTypeId,
        List<ColumnRef> nodeKeyColumns
    ) implements InputField {}

    /**
     * A field in a {@code @table}-annotated input type that represents a legacy composite
     * platform key. The underlying jOOQ record class exposes {@code getXId()} /
     * {@code setXId(String)} accessor methods (where {@code X} is derived from the resolved
     * column name) added by a custom jOOQ code generator; the table has no corresponding column.
     *
     * <p>Only classified for scalar GraphQL {@code ID}-typed fields with no {@code @nodeId}
     * directive, when no real column matches the resolved name but the record class exposes the
     * expected accessor pair. Fields with {@code @nodeId} take the Relay NodeID path and never
     * produce this variant; list-typed fields ({@code [ID!]!}) are rejected at the fallback
     * boundary.
     *
     * <p>No {@link ColumnRef} is carried. {@code getterName} and {@code setterName} are
     * pre-resolved by the classifier so the generator emits the correct call without
     * re-deriving method names. A {@code list} field is intentionally omitted — the classifier
     * guarantees scalar.
     */
    record PlatformIdField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String typeName,
        boolean nonNull,
        String getterName,
        String setterName
    ) implements InputField {}

    /**
     * A field in a {@code @table}-annotated input type whose GraphQL type is itself an input
     * object type with no {@code @table} directive — i.e., a plain grouping type whose fields all
     * map to columns on the <em>parent</em> table rather than a separate SQL table.
     *
     * <p>This is the input-side parallel of {@link no.sikt.graphitron.rewrite.model.ChildField.NestingField}
     * on the output side: both inherit the parent's table context unchanged. The nested fields are
     * resolved at classification time against the same {@link TableRef} as the parent.
     *
     * <p>The mutation generator navigates {@code input.get<Name>().get<Field>()} to reach the
     * nested values. No join or separate table binding is involved.
     */
    record NestingField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String typeName,
        boolean nonNull,
        boolean list,
        List<InputField> fields,
        Optional<ArgConditionRef> condition
    ) implements InputField {}
}
