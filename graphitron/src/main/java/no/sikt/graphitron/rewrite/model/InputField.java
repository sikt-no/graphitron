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
                InputField.NestingField, InputField.UnboundField,
                InputField.LookupKeyField, InputField.SetField {

    /**
     * Carriers admissible as filter input fields on a {@code TableInputArg}. Sibling sealed root
     * to {@link SetField}: both permit value-bearing scalar carriers ({@link ColumnField} /
     * {@link CompositeColumnField}) and FK-target reference carriers
     * ({@link ColumnReferenceField} / {@link CompositeColumnReferenceField}) whose
     * {@code liftedSourceColumns} live on the input's own table. The admissible-carrier shape
     * is "no JOIN context at the emit site" — value carriers source the column from
     * {@link ColumnField#column()} / {@link CompositeColumnField#columns()}, reference carriers
     * from {@link ColumnReferenceField#liftedSourceColumns()} /
     * {@link CompositeColumnReferenceField#liftedSourceColumns()}.
     *
     * <p>{@link NestingField} stays outside the permits set: it never admits as a carrier itself.
 * A non-{@code @table} nested grouping flattens to its leaf carriers at the gate, each
     * leaf rewrapped with a {@link CallSiteExtraction.NestedInputField} access path; a nested
     * {@code @table} input that introduces a second DML target remains compound-entity-mutation
     * territory.
     */
    sealed interface LookupKeyField extends InputField permits ColumnField, CompositeColumnField,
            ColumnReferenceField, CompositeColumnReferenceField {}

    /**
     * Carriers admissible as set-side input fields on a {@code TableInputArg} (the INSERT
     * column-list / UPDATE SET / UPSERT INSERT-arm dispatch surface). Same admitted-carrier
     * set as {@link LookupKeyField}: value-bearing scalar carriers and FK-target reference
     * carriers whose {@code liftedSourceColumns} live on the input's own table.
     */
    sealed interface SetField extends InputField permits ColumnField, CompositeColumnField,
            ColumnReferenceField, CompositeColumnReferenceField {}

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
    ) implements InputField, LookupKeyField, SetField {}

    /**
     * A field in a {@code @table}-annotated input type that uses {@code @reference} to reach a
     * column on a joined table.
     *
     * <p>{@code joinPath} is the resolved FK join path from the input type's own table to the
     * terminal table that holds {@code column}. The path is produced by the same reference-path
     * parser as {@link ChildField.ColumnReferenceField}.
     *
     * <p>When generating WHERE predicates against this field, the generator must JOIN through
     * {@code joinPath} before applying the column predicate.
     *
     * @param extraction translates the wire-format value to the column's typed Java value at the
     *     call-site root. Restricted to {@link CallSiteExtraction.Direct} (the {@code @reference}-
     *     resolved column-equality path) and arity-1 {@link CallSiteExtraction.NodeIdDecodeKeys}
     *     (input-side {@code @nodeId(typeName: T)} reference); arity &ge; 2 routes to
     *     {@link CompositeColumnReferenceField}.
     * @param selfReference {@code true} when this carrier is a <em>self-FK</em> reference — a
     *     same-table {@code @nodeId @reference} whose {@code @reference} names a foreign key back to
 * the carrier's own table. The decoded keys land on the self-FK's child columns, a
     *     pointer to a sibling row, never the row's own identity. {@link UpdateRows} reads this to route a self-FK's
     *     lifted columns wholly to the UPDATE SET partition (a self-FK is a write of "who this row
     *     points at", never identity), in contrast to a cross-table FK reference whose lifted column
     *     can legitimately be the row's own identity. The fact is decided once at the {@code @nodeId}
     *     discrimination site ({@link no.sikt.graphitron.rewrite.NodeIdLeafResolver}); every non-self-FK
     *     construction site sets {@code false}.
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
        List<ColumnRef> liftedSourceColumns,
        boolean selfReference,
        Optional<ArgConditionRef> condition,
        CallSiteExtraction extraction
    ) implements InputField, LookupKeyField, SetField {

        public ColumnReferenceField {
            joinPath = List.copyOf(joinPath);
            liftedSourceColumns = List.copyOf(liftedSourceColumns);
        }
    }

    /**
     * Composite-key input carrier on a {@code @table}-annotated input type. Carries
     * {@code columns} of arity &ge; 2 (arity-1 routes to {@link ColumnField} instead) plus
     * {@code extraction} narrowed at the type level to
     * {@link CallSiteExtraction.NodeIdDecodeKeys}, which is the only extraction shape producing
     * a multi-column tuple (Direct, JooqConvert, EnumValueOf, ContextArg are all single-scalar
     * leaf shapes; NestedInputField is a Map-traversal arm that hosts an inner extraction
     * rather than producing a tuple).
     *
     * <p>The body emitter pairs {@link CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement}
     * with {@link BodyParam.RowEq RowEq} (scalar same-table NodeId equality) or
     * {@link BodyParam.RowIn RowIn} (list filter).
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
    ) implements InputField, LookupKeyField, SetField {

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
     * <p>The arity-1 case lands on the single-column {@link ColumnReferenceField}.
     *
     * <p>{@code selfReference} carries the same self-FK fact as {@link ColumnReferenceField#selfReference()}:
     * {@code true} for a composite same-table {@code @nodeId @reference} (e.g. {@code email}'s
     * {@code inReplyTo}, whose {@code email_in_reply_to_fk} child columns are
     * {@code (mailbox_id, in_reply_to_no)}), driving all-SET routing on UPDATE.
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
        List<ColumnRef> liftedSourceColumns,
        boolean selfReference,
        Optional<ArgConditionRef> condition,
        CallSiteExtraction.NodeIdDecodeKeys extraction
    ) implements InputField, LookupKeyField, SetField {

        public CompositeColumnReferenceField {
            columns = List.copyOf(columns);
            joinPath = List.copyOf(joinPath);
            liftedSourceColumns = List.copyOf(liftedSourceColumns);
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

    /**
     * Input field that does not bind to a SQL column. The defining property is the absence of a
 * column binding, regardless of whether an explicit {@code @condition} is present.
     *
     * <p>{@code condition} folds two cases the classifier used to distinguish:
     * <ul>
     *   <li>{@code condition.isPresent() && condition.get().override()} — the field carries an
     *       explicit {@code @condition(override: true)} (with or without a matching column on the
     *       resolving table). The condition method owns the WHERE predicate entirely; no implicit
     *       column predicate is emitted by construction. This folds together the former
     *       condition-only field and the {@code ColumnField + override:true} case.</li>
     *   <li>{@code condition.isPresent() && !condition.get().override()} — the field carries
     *       {@code @condition(override: false)} but has no matching column. Validator-side
     *       rejection (the classifier admits to keep call-site cascade resolution honest, but
     *       this shape is a schema author bug and {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator GraphitronSchemaValidator} catches it
     *       at the directive's location).</li>
     *   <li>{@code condition.isEmpty()} — the field has no {@code @condition} of its own and no
     *       column resolves on the {@code @table} input's table. Admitted at consumption when the
     *       enclosing arg- or field-level {@code @condition(override: true)} cascade resolves it;
     *       rejected at the field's source location otherwise.</li>
     * </ul>
     *
     * <p>Not a {@link LookupKeyField} / {@link SetField}: those rails require a column tuple to
     * drive the VALUES+JOIN or INSERT/UPDATE columnlist; unbound carriers have neither.
     *
     * <p>{@code attemptedColumnName} carries the name the classifier looked up against the
     * resolving table when {@code condition.isEmpty()} reached the column-miss fall-through; the
     * consumer-side rejection uses it for the Levenshtein "did you mean" hint. {@code null} when
     * no column lookup was attempted (override:true with a matching column, where the classifier
     * collapsed eagerly).
     */
    record UnboundField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String typeName,
        boolean nonNull,
        boolean list,
        Optional<ArgConditionRef> condition,
        String attemptedColumnName
    ) implements InputField {}
}
