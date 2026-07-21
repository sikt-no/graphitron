package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ArgConditionRef;

import java.util.List;
import java.util.Optional;

/**
 * Classifies every field in a GraphQL input object type.
 * The sealed hierarchy mirrors the input-field taxonomy, parallel to {@link ChildField} for output fields.
 *
 * <p>{@link ColumnBackedField}, {@link ColumnBackedReferenceField}, and {@link NestingField} carry
 * an optional {@code condition} — an {@link ArgConditionRef} built from a {@code @condition}
 * directive on the input field definition. When present, the condition method fires as an
 * additional WHERE predicate alongside (or instead of, when {@code override: true}) the
 * auto-generated column predicate.
 */
public sealed interface InputField extends GraphitronField
        permits InputField.ColumnBackedField, InputField.ColumnBackedReferenceField,
                InputField.NestingField, InputField.UnboundField,
                InputField.LookupKeyField, InputField.SetField {

    /**
     * Carriers admissible as filter input fields on a {@code TableInputArg}. Sibling sealed root
     * to {@link SetField}: both permit the value-bearing scalar carrier
     * ({@link ColumnBackedField}) and the FK-target reference carrier
     * ({@link ColumnBackedReferenceField}) whose {@code liftedSourceColumns} live on the input's
     * own table. The admissible-carrier shape is "no JOIN context at the emit site" — the value
     * carrier sources its column(s) from {@link ColumnBackedField#columns()}, the reference
     * carrier from {@link ColumnBackedReferenceField#liftedSourceColumns()}.
     *
     * <p>{@link NestingField} stays outside the permits set: it never admits as a carrier itself.
 * A non-{@code @table} nested grouping flattens to its leaf carriers at the gate, each
     * leaf rewrapped with a {@link CallSiteExtraction.NestedInputField} access path; a nested
     * {@code @table} input that introduces a second DML target remains compound-entity-mutation
     * territory.
     */
    sealed interface LookupKeyField extends InputField permits ColumnBackedField,
            ColumnBackedReferenceField {}

    /**
     * Carriers admissible as set-side input fields on a {@code TableInputArg} (the INSERT
     * column-list / UPDATE SET / UPSERT INSERT-arm dispatch surface). Same admitted-carrier
     * set as {@link LookupKeyField}: the value-bearing scalar carrier and the FK-target
     * reference carrier whose {@code liftedSourceColumns} live on the input's own table.
     */
    sealed interface SetField extends InputField permits ColumnBackedField,
            ColumnBackedReferenceField {}

    /**
     * A field in a {@code @table}-annotated input type, successfully resolved to one or more SQL
     * columns on the type's own table. Arity is a column count on this one leaf, not a leaf
     * dimension; consumers branch on {@link #isComposite()}.
     *
     * <p>Each {@link ColumnRef} carries the jOOQ identity of a column: SQL name, Java constant
     * name, and Java class. The GraphQL layer ({@code typeName}, {@code nonNull}, {@code list})
     * describes the shape of the value the caller supplies.
     *
     * <p>If a field's column cannot be resolved at build time the entire containing
     * {@link GraphitronType.TableInputType} is replaced by a
     * {@link GraphitronType.UnclassifiedType}.
     *
     * @param extraction translates the wire-format value to the columns' typed Java values at
     *     the call-site root. {@link CallSiteExtraction.Direct} (column-equality path) and the
     *     other single-scalar shapes imply arity 1 by the constructor invariant;
     *     {@link CallSiteExtraction.NodeIdDecodeKeys} (NodeId-encoded filter) is the only
     *     extraction producing a multi-column tuple and is required at arity &ge; 2. The body
     *     emitter pairs {@link CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement} with
     *     {@link BodyParam.RowEq RowEq} (scalar same-table NodeId equality) or
     *     {@link BodyParam.RowIn RowIn} (list filter) on the composite shape.
     */
    record ColumnBackedField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String typeName,
        boolean nonNull,
        boolean list,
        List<ColumnRef> columns,
        Optional<ArgConditionRef> condition,
        CallSiteExtraction extraction
    ) implements InputField, LookupKeyField, SetField {

        public ColumnBackedField {
            columns = List.copyOf(columns);
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("InputField.ColumnBackedField requires at least one column");
            }
            // Deferred-generalization seam, not a modeling truth: @nodeId is currently the only
            // multi-column trigger, so a multi-column carrier always decodes a node key (and, by
            // corollary, every single-scalar extraction is single-column). Loosen this when a
            // plain multi-column input shape arrives instead of building on it.
            if (columns.size() > 1 && !(extraction instanceof CallSiteExtraction.NodeIdDecodeKeys)) {
                throw new IllegalArgumentException(
                    "InputField.ColumnBackedField '" + name + "' with arity " + columns.size()
                    + " requires NodeIdDecodeKeys extraction; got " + extraction);
            }
        }
        /**
         * Arity classified once: {@code true} when this carrier spans more than one column (a
         * composite node key). Every consumer branches on this accessor rather than re-evaluating
         * the size predicate.
         */
        public boolean isComposite() { return columns.size() > 1; }
    }

    /**
     * A field in a {@code @table}-annotated input type that uses {@code @reference} (or an
     * FK-target {@code @nodeId(typeName: T)}) to reach one or more columns on a joined table.
     * Arity is a column count on this one leaf, not a leaf dimension; consumers branch on
     * {@link #isComposite()}.
     *
     * <p>{@code joinPath} is the resolved FK join path from the input type's own table to the
     * terminal table that holds {@code columns}. The path is produced by the same reference-path
     * parser as {@link ChildField.ColumnBackedReferenceField}.
     *
     * <p>When generating WHERE predicates against this field, the generator must JOIN through
     * {@code joinPath} before applying the column predicate.
     *
     * @param extraction translates the wire-format value to the columns' typed Java values at the
     *     call-site root. {@link CallSiteExtraction.Direct} (the {@code @reference}-resolved
     *     column-equality path) implies arity 1 by the constructor invariant;
     *     {@link CallSiteExtraction.NodeIdDecodeKeys} (input-side {@code @nodeId(typeName: T)}
     *     reference) is required at arity &ge; 2.
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
    record ColumnBackedReferenceField(
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
        CallSiteExtraction extraction
    ) implements InputField, LookupKeyField, SetField {

        public ColumnBackedReferenceField {
            columns = List.copyOf(columns);
            joinPath = List.copyOf(joinPath);
            liftedSourceColumns = List.copyOf(liftedSourceColumns);
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("InputField.ColumnBackedReferenceField requires at least one column");
            }
            // Same deferred-generalization seam as ColumnBackedField: no plain multi-column
            // input reference exists today, so a multi-column carrier always decodes a node key.
            if (columns.size() > 1 && !(extraction instanceof CallSiteExtraction.NodeIdDecodeKeys)) {
                throw new IllegalArgumentException(
                    "InputField.ColumnBackedReferenceField '" + name + "' with arity " + columns.size()
                    + " requires NodeIdDecodeKeys extraction; got " + extraction);
            }
        }
        /**
         * Arity classified once: {@code true} when this carrier spans more than one column (a
         * composite node key). Every consumer branches on this accessor rather than re-evaluating
         * the size predicate.
         */
        public boolean isComposite() { return columns.size() > 1; }
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
