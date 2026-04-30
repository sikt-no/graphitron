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
 */
public sealed interface InputField extends GraphitronField
        permits InputField.ColumnField, InputField.ColumnReferenceField,
                InputField.NodeIdField, InputField.NodeIdReferenceField,
                InputField.IdReferenceField,
                InputField.NodeIdInFilterField,
                InputField.NestingField {

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
    /**
     * @param extraction how to translate the wire-format value to the column's typed Java
     *     value at the call-site root. Today's classifier produces {@link CallSiteExtraction.Direct}
     *     for the column-equality path. Post-R50, the slot also hosts
     *     {@link CallSiteExtraction.NodeIdDecodeKeys} for arity-1 NodeId-encoded filter fields
     *     (see R50 phase e); the body emitter pairs {@code Direct} with
     *     {@link BodyParam.ColumnPredicate.Eq Eq} / {@link BodyParam.ColumnPredicate.In In} and
     *     {@code NodeIdDecodeKeys.SkipMismatchedElement} with the same arms over decoded key
     *     values.
     */
    record ColumnField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String typeName,
        boolean nonNull,
        boolean list,
        ColumnRef column,
        Optional<ArgConditionRef> condition,
        CallSiteExtraction extraction
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
     * A field in a {@code @table}-annotated input type whose GraphQL type is scalar {@code ID}
     * and which carries {@code @nodeId(typeName: "X")} pointing at a {@link
     * no.sikt.graphitron.rewrite.model.GraphitronType.NodeType} reachable from the input type's
     * own table via {@code joinPath}.
     *
     * <p>Classified when {@code typeName} resolves to a {@code NodeType} and a FK join path from
     * the input type's own table to that type's table exists (either auto-inferred from a single
     * FK or specified explicitly via {@code @reference}). The classifier guarantees scalar (list
     * inputs collapse the containing {@code TableInputType} to {@code UnclassifiedType}).
     *
     * <p>The generator decodes the base64 composite ID and binds each unpacked value to its
     * target column via {@code NodeIdStrategy.unpackIdValues} / {@code hasIds} / {@code hasId},
     * JOINing through {@code joinPath} before applying the predicate (or collapsing to a direct
     * same-table column assignment when the own-table mirrors the target's key columns).
     */
    record NodeIdReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String typeName,
        boolean nonNull,
        TableRef parentTable,
        String nodeTypeId,
        List<ColumnRef> nodeKeyColumns,
        List<JoinStep> joinPath,
        Optional<ArgConditionRef> condition
    ) implements InputField {}

    /**
     * A filter field typed {@code ID!} or {@code [ID!]} whose predicate is a
     * {@code has<Qualifier>(s)} method on the jOOQ record class of the input's resolved table
     * (i.e. the FK source). The method is emitted by {@code KjerneJooqGenerator} from a single
     * FK out of that table, identified here by {@code fkName}.
     *
     * <p>{@code targetTypeName} is the GraphQL type the IDs encode (from
     * {@code @nodeId(typeName:)} on the canonical forms, or synthesized from the FK's target
     * table on the shim arm). {@code fkName} is the jOOQ FK constraint name (from
     * {@code @reference(path: [{key:}])} when explicit, or inferred by walking FKs from the
     * resolved table to the table backing {@code targetTypeName} when unique). {@code qualifier}
     * is the UpperCamelCase string returned by the local reproduction of
     * {@code KjerneJooqGenerator.getQualifier(fk)} (e.g. {@code "StudieprogramId"}); code
     * generation derives the predicate method names by prepending {@code "has"}.
     *
     * <p>{@code synthesized} is {@code true} when the variant was emitted by the column-miss
     * shim arm (legacy {@code @field(name:)}-only SDL); the classifier also logs a per-site
     * WARN in that case.
     */
    record IdReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String typeName,
        boolean nonNull,
        boolean list,
        String targetTypeName,
        String fkName,
        String qualifier,
        boolean synthesized
    ) implements InputField {}

    /**
     * A {@code [ID!]} field on a {@code @table} input type whose {@code @nodeId(typeName:)}
     * references the <em>same</em> table as the input itself. Semantics: "filter results to rows
     * whose composite primary key matches one of these decoded node IDs"; a primary-key IN
     * predicate, not a FK join.
     *
     * <p>The generator decodes each base64 node ID into its component PK column values, then emits
     * {@code WHERE (pk1, pk2, ...) IN ((v1a, v1b), (v2a, v2b), ...)}. No join path is involved.
     *
     * <p>{@code nodeTypeId} and {@code nodeKeyColumns} come from
     * {@link no.sikt.graphitron.rewrite.JooqCatalog#nodeIdMetadata(String)} on the target table,
     * which is the same table the enclosing input type already binds to.
     */
    record NodeIdInFilterField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String nodeTypeId,
        List<ColumnRef> nodeKeyColumns
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
