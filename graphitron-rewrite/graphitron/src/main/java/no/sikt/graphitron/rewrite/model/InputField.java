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
                InputField.CompositeColumnField, InputField.CompositeColumnReferenceField,
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
     *
     * @param extraction translates the wire-format value to the column's typed Java value at
     *     the call-site root. Restricted to {@link CallSiteExtraction.Direct} (column-equality
     *     path) and arity-1 {@link CallSiteExtraction.NodeIdDecodeKeys} (NodeId-encoded filter);
     *     arity &ge; 2 NodeId tuples route to {@link CompositeColumnField} instead.
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
     *
     * @param extraction translates the wire-format value to the column's typed Java value at the
     *     call-site root. Restricted to {@link CallSiteExtraction.Direct} (the {@code @reference}-
     *     resolved column-equality path) and arity-1 {@link CallSiteExtraction.NodeIdDecodeKeys}
     *     (input-side {@code @nodeId(typeName: T)} reference); arity &ge; 2 routes to
     *     {@link CompositeColumnReferenceField}.
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
        Optional<ArgConditionRef> condition,
        CallSiteExtraction extraction
    ) implements InputField {}

    /**
     * Composite-key input carrier on a {@code @table}-annotated input type. Carries
     * {@code columns} of arity &ge; 2 (arity-1 routes to {@link ColumnField} instead) plus
     * {@code extraction} narrowed at the type level to
     * {@link CallSiteExtraction.NodeIdDecodeKeys} -- the only extraction shape that produces
     * a multi-column tuple (Direct, JooqConvert, EnumValueOf, TextMapLookup, ContextArg are
     * all single-scalar leaf shapes; NestedInputField is a Map-traversal arm that hosts an
     * inner extraction rather than producing a tuple). Lands as the post-R50 successor for the
     * arity > 1 cases of the retired wire-shape variants {@code NodeIdField} and
     * {@code NodeIdInFilterField}.
     *
     * <p>The body emitter pairs {@link CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement}
     * with {@link BodyParam.RowEq RowEq} (for scalar same-table NodeId equality) or
     * {@link BodyParam.RowIn RowIn} (for list filter, the post-R50 successor of the retired
     * {@code NodeIdInFilterField}).
     */
    record CompositeColumnField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String typeName,
        boolean nonNull,
        boolean list,
        List<ColumnRef> columns,
        Optional<ArgConditionRef> condition,
        CallSiteExtraction.NodeIdDecodeKeys extraction
    ) implements InputField {

        public CompositeColumnField {
            columns = List.copyOf(columns);
            if (columns.size() < 2) {
                throw new IllegalArgumentException(
                    "InputField.CompositeColumnField requires arity >= 2 (got " + columns.size() + "); arity-1 routes to ColumnField");
            }
        }
    }

    /**
     * Composite-key input reference carrier on a {@code @table}-annotated input type whose
     * {@code @nodeId(typeName: T)} target NodeType has multiple key columns. Carries
     * {@code columns} = the target NodeType's keyColumns plus the resolved {@code joinPath}
     * from the input's own table to the target table. {@code extraction} is narrowed to
     * {@link CallSiteExtraction.NodeIdDecodeKeys} at the type level, same rationale as
     * {@link CompositeColumnField}.
     *
     * <p>Post-R50 successor for the arity > 1 case of the retired wire-shape
     * {@code NodeIdReferenceField}; the arity-1 case lands on the single-column
     * {@link ColumnReferenceField}.
     */
    record CompositeColumnReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String typeName,
        boolean nonNull,
        boolean list,
        List<ColumnRef> columns,
        List<JoinStep> joinPath,
        Optional<ArgConditionRef> condition,
        CallSiteExtraction.NodeIdDecodeKeys extraction
    ) implements InputField {

        public CompositeColumnReferenceField {
            columns = List.copyOf(columns);
            if (columns.size() < 2) {
                throw new IllegalArgumentException(
                    "InputField.CompositeColumnReferenceField requires arity >= 2 (got " + columns.size() + "); arity-1 routes to ColumnReferenceField");
            }
        }
    }

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
