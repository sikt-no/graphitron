package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ArgConditionRef;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;

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
                InputField.ConditionOwnedField,
                InputField.LookupKeyField, InputField.SetField {

    /**
     * Carriers admissible as filter input fields on a {@code TableInputArg}. Sibling sealed root
     * to {@link SetField}: both permit the value-bearing scalar carrier
     * ({@link ColumnBackedField}) and the FK-target reference carrier
     * ({@link ColumnBackedReferenceField}). The admissible-carrier shape is "own-table columns at
     * the emit site": the value carrier sources its column(s) from
     * {@link ColumnBackedField#columns()}, the reference carrier from its
     * {@link FilterBinding.Local} binding.
     *
     * <p>Membership in this permits set is therefore necessary but no longer sufficient for a
     * reference carrier: a {@link FilterBinding.Remote} one reaches its value through a join and has
     * no own-table tuple, so the rail that admits it gates per instance
     * ({@code FieldBuilder.classifyPlainLookupKeyArg} on the lookup side,
     * {@code MutationInputResolver.admitMutationInputFields} on INSERT).
     *
     * <p>{@link NestingField} stays outside the permits set: it never admits as a carrier itself.
     * A nested grouping flattens to its leaf carriers at the gate, each
     * leaf rewrapped with a {@link CallSiteExtraction.NestedInputField} access path; a nested
     * input introducing a second DML target remains compound-entity-mutation
     * territory.
     */
    sealed interface LookupKeyField extends InputField permits ColumnBackedField,
            ColumnBackedReferenceField {}

    /**
     * Carriers admissible as set-side input fields on a {@code TableInputArg} (the INSERT
     * column-list / UPDATE SET / UPSERT INSERT-arm dispatch surface). Same admitted-carrier
     * set as {@link LookupKeyField}, and the same per-instance qualification: a reference carrier
     * is writable only when its {@link FilterBinding} is {@link FilterBinding.Local}.
     */
    sealed interface SetField extends InputField permits ColumnBackedField,
            ColumnBackedReferenceField {}

    /**
     * An input field successfully resolved to one or more SQL columns on the consuming
     * field's table. Arity is a column count on this one leaf, not a leaf
     * dimension; consumers branch on {@link #isComposite()}.
     *
     * <p>Each {@link ColumnRef} carries the jOOQ identity of a column: SQL name, Java constant
     * name, and Java class. The GraphQL layer ({@code typeName}, {@code nonNull}, {@code list})
     * describes the shape of the value the caller supplies.
     *
     * <p>If a field's column cannot be resolved at build time, the resolution rejects at
     * the consuming coordinate (the surrounding argument or mutation field).
     *
     * @param extraction translates the wire-format value to the columns' typed Java values at
     *     the call-site root. {@link CallSiteExtraction.Direct} (column-equality path) and the
     *     other single-scalar shapes imply arity 1 by the constructor invariant;
     *     {@link CallSiteExtraction.NodeIdDecodeKeys} (NodeId-encoded filter) is the only
     *     extraction producing a multi-column tuple and is required at arity &ge; 2. The body
     *     emitter pairs it with {@link BodyParam.RowEq RowEq} (scalar same-table NodeId equality) or
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
     * An input field that uses {@code @reference} (or an
     * FK-target {@code @nodeId(typeName: T)}) to reach one or more columns on a joined table.
     * Arity is a column count on this one leaf, not a leaf dimension; consumers branch on
     * {@link #isComposite()}.
     *
     * <p>{@code joinPath} is the resolved FK join path from the consuming field's table to the
     * terminal table that holds {@code columns}. The path is produced by the same reference-path
     * parser as {@link ChildField.ColumnBackedReferenceField}.
     *
     * <p>{@code binding} says where the implicit value predicate lands. {@link FilterBinding.Local}
     * carries a tuple on the consuming field's own table: the resolved column for a plain
     * {@code @reference}, or the FK-child columns a direct FK-target {@code @nodeId} lifts to.
     * {@link FilterBinding.Remote} means the predicate binds {@code columns} on the terminal table
     * inside a correlated {@code EXISTS} (a plain {@code @reference} reaching a joined column, or a
     * translated FK-target {@code @nodeId} whose target-side FK columns are not the NodeType's key
     * columns). Only a {@code Local} carrier is writable; see {@link LookupKeyField}.
     *
     * @param extraction translates the wire-format value to the columns' typed Java values at the
     *     call-site root. {@link CallSiteExtraction.Direct} (the {@code @reference}-resolved
     *     column-equality path) implies arity 1 by the constructor invariant;
     *     {@link CallSiteExtraction.NodeIdDecodeKeys} (input-side {@code @nodeId(typeName: T)}
     *     reference) is required at arity &ge; 2.
     * @param selfReference {@code true} when this carrier is a <em>self-FK</em> reference: a
     *     same-table {@code @nodeId @reference} whose {@code @reference} names a foreign key back to
     *     the carrier's own table. The decoded keys land on the self-FK's child columns, a
     *     pointer to a sibling row, never the row's own identity. {@link UpdateRows} reads this to route a self-FK's
     *     lifted columns wholly to the UPDATE SET partition (a self-FK is a write of "who this row
     *     points at", never identity), in contrast to a cross-table FK reference whose lifted column
     *     can legitimately be the row's own identity. The fact is decided once at the {@code @nodeId}
     *     discrimination site ({@link no.sikt.graphitron.rewrite.NodeIdLeafResolver}); every non-self-FK
     *     construction site sets {@code false}. On a {@link FilterBinding.Remote} carrier the value is
     *     unreachable rather than merely unused: the flag's one reader is the UPDATE SET partition,
     *     which refuses a remote binding at its own gate before reading it.
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
        FilterBinding binding,
        boolean selfReference,
        Optional<ArgConditionRef> condition,
        CallSiteExtraction extraction
    ) implements InputField, LookupKeyField, SetField {

        public ColumnBackedReferenceField {
            columns = List.copyOf(columns);
            joinPath = List.copyOf(joinPath);
            Objects.requireNonNull(binding, "binding");
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("InputField.ColumnBackedReferenceField requires at least one column");
            }
            if (binding instanceof FilterBinding.Remote && joinPath.isEmpty()) {
                throw new IllegalArgumentException(
                    "InputField.ColumnBackedReferenceField '" + name + "' binds Remote but carries an"
                    + " empty joinPath; a remote predicate has no terminal table to reach");
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
     * An input field whose GraphQL type is itself an input
     * object type, i.e. a plain grouping type whose fields all
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
     * Input field whose explicit {@code @condition(override: true)} method owns the WHERE
     * predicate entirely. No implicit column predicate is emitted by construction, so whether the
     * field's name also resolves a column on the resolving table is deliberately not recorded:
     * the column would be dead storage either way, and both classification outcomes (column
     * resolved, column missing) mint this one carrier so it means exactly one thing.
     *
     * <p>The compact constructor pins the defining fact: the condition is present with
     * {@code override: true}. Consumers branch on carrier identity, never on a
     * {@code condition().isPresent() && override()} re-derivation: the filter walk fires the
     * method unconditionally (every authored {@code @condition} produces SQL), the DML walkers
     * reject it as an unsupported write shape at their own arms, and the mutation admission
     * admits it on UPDATE / DELETE and rejects it on INSERT (no WHERE clause to bind into).
     *
     * <p>Not a {@link LookupKeyField} / {@link SetField}: those rails require a column tuple to
     * drive the VALUES+JOIN or INSERT/UPDATE columnlist.
     */
    record ConditionOwnedField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String typeName,
        boolean nonNull,
        boolean list,
        ArgConditionRef condition
    ) implements InputField {

        public ConditionOwnedField {
            java.util.Objects.requireNonNull(condition, "condition");
            if (!condition.override()) {
                throw new IllegalArgumentException(
                    "InputField.ConditionOwnedField '" + name
                    + "' requires @condition(override: true); got override: false");
            }
        }
    }

    /**
     * Input field that does not bind to a SQL column: the classifier looked
     * {@code attemptedColumnName} up against the resolving table and found nothing. A field whose
     * explicit {@code @condition(override: true)} owns the predicate is {@link ConditionOwnedField},
     * never this carrier, so the miss here is always a genuine one.
     *
     * <p>{@code condition} distinguishes the two remaining cases:
     * <ul>
     *   <li>{@code condition.isPresent()} ({@code override: false} by construction, the
     *       {@code override: true} shape being {@link ConditionOwnedField}): the malformed shape.
     *       {@code override: false} means the implicit column predicate is required to compose
     *       with the explicit method, and there is no column to bind. The fact is minted into the
     *       build diagnostics at classification, keyed by this definition and the resolving
     *       table; the consumer's walk still fires the authored method (every {@code @condition}
     *       produces SQL) while the diagnostic fails the build.</li>
     *   <li>{@code condition.isEmpty()}: the field has no {@code @condition} of its own. Admitted
     *       at consumption when an enclosing arg- or field-level
     *       {@code @condition(override: true)} cascade resolves it; the use-keyed cascade verdict
     *       is minted otherwise.</li>
     * </ul>
     *
     * <p>Not a {@link LookupKeyField} / {@link SetField}: those rails require a column tuple to
     * drive the VALUES+JOIN or INSERT/UPDATE columnlist; unbound carriers have neither.
     *
     * <p>{@code attemptedColumnName} is always the name the lookup missed with ({@code @field}
     * binding or the SDL name); the cascade verdict renders it into the Levenshtein "did you
     * mean" hint.
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
    ) implements InputField {

        public UnboundField {
            java.util.Objects.requireNonNull(attemptedColumnName, "attemptedColumnName");
            condition.ifPresent(c -> {
                if (c.override()) {
                    throw new IllegalArgumentException(
                        "InputField.UnboundField '" + name + "' with @condition(override: true) is"
                        + " the ConditionOwnedField carrier's shape; UnboundField carries only the"
                        + " genuine miss");
                }
            });
        }
    }
}
