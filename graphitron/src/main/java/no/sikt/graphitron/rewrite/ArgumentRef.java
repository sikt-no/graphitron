package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FilterBinding;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TableRef;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;

/**
 * Classification of a single GraphQL argument.
 *
 * <p>Produced once per argument by {@code FieldBuilder.classifyArguments()}. Most variants are
 * projected into generation-ready model types (e.g. {@code WhereFilter}, {@code OrderBySpec},
 * {@code PaginationSpec}, {@code LookupMapping}) by separate projection helpers
 * ({@code projectForFilter}, {@code projectForLookup}) and never reach the generators directly.
 *
 * <p>The exception is {@link InputTypeArg.TableInputArg}, which is carried verbatim on
 * {@link no.sikt.graphitron.rewrite.model.MutationField.DmlTableField} so the mutation emitters
 * can read its {@code inputTable}, {@code fieldBindings}, and {@code fields} directly. It is
 * the only argument-classification type that crosses the model/generator boundary.
 *
 * <p>See {@code docs/architecture/reference/argument-resolution.adoc} for the design and projection semantics.
 *
 * <h2>Variants</h2>
 * <ul>
 *   <li>{@link ScalarArg.ColumnBackedArg} — scalar arg bound to one or more jOOQ columns
 *       (a multi-column carrier decodes once per row: the composite-PK NodeId case).</li>
 *   <li>{@link ScalarArg.ColumnBackedReferenceArg} — FK-target {@code @nodeId(typeName: T)}
 *       scalar arg with a resolved single-hop {@code joinPath}; carries the target NodeType's
 *       key columns (arity 1..N).</li>
 *   <li>{@link ScalarArg.ConditionOwnedArg} — {@code @nodeId} scalar arg whose
 *       {@code @condition(override: true)} method owns the whole {@code WHERE} contribution
 *       because no route to the target table resolved.</li>
 *   <li>{@link ScalarArg.UnboundArg} — scalar arg whose column could not be resolved;
 *       surfaced as a validation error.</li>
 *   <li>{@link InputTypeArg.TableInputArg} — table-resolved input arg; carries per-field
 *       column bindings.</li>
 *   <li>{@link InputTypeArg.PlainInputArg} — input type without {@code @table}; only meaningful
 *       when paired with {@code @condition}.</li>
 *   <li>{@link OrderByArg} — argument carrying {@code @orderBy}; projects into {@code OrderBySpec}.</li>
 *   <li>{@link PaginationArgRef} — one of {@code first}/{@code last}/{@code after}/{@code before};
 *       projects into {@code PaginationSpec}. "Ref" suffix avoids collision with
 *       {@code PaginationSpec.PaginationArg}.</li>
 *   <li>{@link UnclassifiedArg} — argument that did not fit any other variant; surfaced as a
 *       validation error.</li>
 * </ul>
 */
public sealed interface ArgumentRef {
    String name();
    String typeName();
    boolean nonNull();
    boolean list();

    /** Scalar-valued argument (not an input-type). */
    sealed interface ScalarArg extends ArgumentRef {

        /**
         * Scalar arg resolved to one or more jOOQ columns on the field's own table. Arity is a
         * column count on this one leaf, not a leaf dimension; consumers branch on
         * {@link #isComposite()}. A multi-column carrier is the composite-PK NodeId case: one
         * wire-format base64 id (or list of them) decodes once per row at the arg layer into a
         * {@code Record<N>}, and bindings against {@code columns} index the Record positionally
         * (carrier-side analogue of {@code LookupArg.DecodedRecord} — {@code projectForLookup}
         * lifts it into that shape when {@code isLookupKey} is set).
         *
         * <p>{@code argCondition} and {@code suppressedByFieldOverride} drive the four-state
         * projection table; see {@code docs/architecture/reference/argument-resolution.adoc}.
         * {@code isLookupKey} reflects the presence of {@code @lookupKey} at classify time
         * so projections (notably {@code projectForLookup}) never re-read the SDL directive.
         *
         * <p>{@code extraction} is {@link CallSiteExtraction.NodeIdDecodeKeys} on every
         * multi-column instance (the constructor invariant below; the only arms producing a
         * multi-column tuple), and any single-scalar arm at arity 1.
         *
         * <p>{@code joinPath} is empty for the common local-column case.
         * When the arg carries {@code @reference(path:)} reaching a column on a <em>joined</em>
         * table, it holds the resolved FK join path from the field's own table to the terminal
         * table that holds the column; {@code projectFilters} then wraps the predicate in a
         * {@link no.sikt.graphitron.rewrite.model.BodyParam.RemoteColumnPredicate} (correlated
         * EXISTS). Which of the two applies is stated by {@code binding} rather than inferred from
         * the path being empty, so the local-vs-remote fork is one exhaustive switch shared with
         * {@link ColumnBackedReferenceArg}; see {@link FilterBinding}.
         *
         * <p>On this carrier both arms bind {@code columns()} (there is one column slot, and the
         * composite-PK node key rides it), so {@link FilterBinding.Local} restates {@code columns()}
         * and the compact constructor checks the two agree. That is a derived slot, which the
         * constructor can police; the empty-path sentinel it replaces was invisible to every switch.
         */
        record ColumnBackedArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            List<ColumnRef> columns,
            CallSiteExtraction extraction,
            Optional<ArgConditionRef> argCondition,
            boolean suppressedByFieldOverride,
            boolean isLookupKey,
            List<JoinStep> joinPath,
            FilterBinding binding
        ) implements ScalarArg {

            public ColumnBackedArg {
                requireNonNull(columns, "columns");
                requireNonNull(binding, "binding");
                columns = List.copyOf(columns);
                joinPath = List.copyOf(joinPath);
                if (columns.isEmpty()) {
                    throw new IllegalArgumentException("ColumnBackedArg requires at least one column");
                }
                if (binding instanceof FilterBinding.Remote && joinPath.isEmpty()) {
                    throw new IllegalArgumentException(
                        "ColumnBackedArg '" + name + "' binds Remote but carries an empty joinPath;"
                        + " a remote predicate has no terminal table to reach");
                }
                if (binding instanceof FilterBinding.Local(var ownTableColumns)
                        && ownTableColumns.size() != columns.size()) {
                    throw new IllegalArgumentException(
                        "ColumnBackedArg '" + name + "' binds Local over " + ownTableColumns.size()
                        + " column(s) but carries " + columns.size()
                        + "; on this carrier the local tuple is columns()");
                }
                // Deferred-generalization seam, not a modeling truth: @nodeId is currently the
                // only multi-column trigger, so a multi-column carrier always decodes a node key.
                // Loosen this when a plain multi-column argument shape arrives instead of
                // building on it.
                if (columns.size() > 1 && !(extraction instanceof CallSiteExtraction.NodeIdDecodeKeys)) {
                    throw new IllegalArgumentException(
                        "ColumnBackedArg '" + name + "' with arity " + columns.size()
                        + " requires NodeIdDecodeKeys extraction; got " + extraction);
                }
            }
            /**
             * Arity classified once: {@code true} when this carrier spans more than one column (a
             * composite node key). Every consumer branches on this accessor rather than
             * re-evaluating the size predicate.
             */
            public boolean isComposite() { return columns.size() > 1; }
        }

        /**
         * FK-target {@code @nodeId(typeName: T)} scalar arg: the target's encoded ids decode into
         * keys of the related NodeType {@code T} (arity 1..N; consumers branch on
         * {@link #isComposite()}), and the predicate filters rows on the FK source columns
         * reachable through {@code joinPath}.
         *
         * <p>Mirrors {@link no.sikt.graphitron.rewrite.model.InputField.ColumnBackedReferenceField}
         * shape-for-shape on the argument side. {@code columns} are the target NodeType's key
         * columns; {@code joinPath} resolves the single-hop FK from the field's containing table
         * to {@code T.table()}. {@code binding} decides where the value predicate lands:
         * {@link FilterBinding.Local} when the FK's target-side columns are the target's key columns
         * so the decoded keys lift to FK-child columns on this table (bare
         * {@link no.sikt.graphitron.rewrite.model.BodyParam.Eq} / {@code In} / {@code RowEq} /
         * {@code RowIn}), {@link FilterBinding.Remote} when they genuinely differ so the predicate
         * binds {@code columns} on the target table inside a correlated {@code EXISTS}.
         *
         * <p>{@code extraction} narrows to {@link CallSiteExtraction.NodeIdDecodeKeys} at every
         * arity, whose one failure mode throws: a malformed or wrong-type id on an authored filter
         * is a client mistake rather than a narrower result set.
         *
         * <p>No {@code isLookupKey} slot: FK-target is a filter, not a lookup. The carrier flows
         * through {@code projectFilters} into the standard {@code GeneratedConditionFilter}
         * pipeline, not {@code LookupMappingResolver}.
         */
        record ColumnBackedReferenceArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            List<ColumnRef> columns,
            List<JoinStep> joinPath,
            FilterBinding binding,
            CallSiteExtraction.NodeIdDecodeKeys extraction,
            Optional<ArgConditionRef> argCondition,
            boolean suppressedByFieldOverride
        ) implements ScalarArg {

            public ColumnBackedReferenceArg {
                requireNonNull(columns, "columns");
                requireNonNull(binding, "binding");
                columns = List.copyOf(columns);
                joinPath = List.copyOf(joinPath);
                if (columns.isEmpty()) {
                    throw new IllegalArgumentException("ColumnBackedReferenceArg requires at least one column");
                }
                if (binding instanceof FilterBinding.Remote && joinPath.isEmpty()) {
                    throw new IllegalArgumentException(
                        "ColumnBackedReferenceArg '" + name + "' binds Remote but carries an empty"
                        + " joinPath; a remote predicate has no terminal table to reach");
                }
            }
            /**
             * Arity classified once: {@code true} when this carrier spans more than one column (a
             * composite node key). Every consumer branches on this accessor rather than
             * re-evaluating the size predicate.
             */
            public boolean isComposite() { return columns.size() > 1; }
        }

        /**
         * Scalar {@code @nodeId} arg whose explicit {@code @condition(override: true)} method owns
         * the {@code WHERE} predicate entirely: no route from the field's table to the node type's
         * table resolved, and the author took responsibility instead. The argument coordinate's
         * counterpart to {@link no.sikt.graphitron.rewrite.model.InputField.ConditionOwnedField},
         * with the same defining fact and the same reason for having no columns: there is nothing
         * for the generator to bind, and a column slot would be dead storage.
         *
         * <p>The method receives the resolving table (each branch's own alias on a multitable
         * consumer) plus the raw wire id, and decodes it through the generated {@code NodeIdEncoder}
         * helpers. The compact constructor pins {@code override: true}; consumers branch on carrier
         * identity rather than re-deriving it from the condition.
         */
        record ConditionOwnedArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            ArgConditionRef condition
        ) implements ScalarArg {

            public ConditionOwnedArg {
                java.util.Objects.requireNonNull(condition, "condition");
                if (!condition.override()) {
                    throw new IllegalArgumentException(
                        "ArgumentRef.ScalarArg.ConditionOwnedArg '" + name
                        + "' requires @condition(override: true); got override: false");
                }
            }
        }

        /**
         * Scalar arg whose column could not be resolved on the target table;
         * surfaced as a validation error with a candidate hint.
         */
        record UnboundArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            String attemptedColumnName,
            String reason
        ) implements ScalarArg {}
    }

    /** Input-typed argument: the GraphQL type is an input object. */
    sealed interface InputTypeArg extends ArgumentRef {

        /**
         * Input argument resolved against a known table: its fields resolve to columns on
         * {@code inputTable}. Used by composite-key lookups and by mutations. The table comes from
         * the consuming field (a {@code @mutation} write target, or the lookup's return-type
         * table), never from a {@code @table} on the input type, which is deprecated and ignored.
         *
         * <p>{@code lookupKeyFields} / {@code setFields} are the typed partition of {@code fields}.
         * The only verbs constructing a {@code TableInputArg} are INSERT and the query-side
         * composite-key lookup (UPDATE and DELETE ride their walker carriers); for both,
         * {@code setFields} is empty and every admissible input field flows into
         * {@code lookupKeyFields}. Both lists are sealed on {@link InputField.LookupKeyField} /
         * {@link InputField.SetField} respectively; each permits the value carrier
         * ({@code ColumnBackedField}) and the FK-target reference carrier
         * ({@code ColumnBackedReferenceField}). Admissibility of a reference carrier is per-instance
         * rather than per-type: only a {@link FilterBinding.Local} one has own-table columns to bind,
         * and the rails gate on that ({@code MutationInputResolver.admitMutationInputFields} for
         * INSERT, {@code FieldBuilder.classifyPlainLookupKeyArg} for the query-side lookup), because
         * {@link #of} is a pure factory with no diagnostic channel and no verb to distinguish them.
         * Construct via {@link #of} so the partition has a single derivation path.
         *
         * <p>{@code fieldBindings} is {@code List<InputColumnBindingGroup>}: one group per
         * WHERE-bound input field. {@link InputColumnBindingGroup.MapGroup} for an arity-1
         * {@link InputField.ColumnBackedField} carrier,
         * {@link InputColumnBindingGroup.DecodedRecordGroup} for a composite one (the
         * composite-PK NodeId case where decode runs once per row at the arg layer into a
         * {@code Record<N>}).
         */
        record TableInputArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            TableRef inputTable,
            List<InputColumnBindingGroup> fieldBindings,
            Optional<ArgConditionRef> argCondition,
            List<InputField> fields,
            List<InputField.LookupKeyField> lookupKeyFields,
            List<InputField.SetField> setFields
        ) implements InputTypeArg {

            public TableInputArg {
                fieldBindings = List.copyOf(fieldBindings);
                fields = List.copyOf(fields);
                lookupKeyFields = List.copyOf(lookupKeyFields);
                setFields = List.copyOf(setFields);
            }

            /**
             * Factory: every top-level admissible carrier goes to {@code lookupKeyFields}, with an
             * empty {@code setFields}. The only callers are INSERT and the query-side composite-key
             * lookup (UPDATE and DELETE ride their walker carriers); neither has a SET partition,
             * and INSERT walks {@code fields()} directly for VALUES emit, so an empty
             * {@code setFields} is correct.
             *
             * <p>A nested non-{@code @table} grouping input ({@link InputField.NestingField})
             * is admitted by flattening onto the outer table, but {@code lookupKeyFields} is left as
             * the top-level carrier filter (a {@code NestingField} is not a {@code LookupKeyField}, so
             * it does not appear here). The flat leaf partition that carries the nested wire access
             * path lives where it is actually consumed: on the {@code UpdateRows} / {@code DeleteRows}
             * walker carriers ({@code SetColumn} / {@code KeyColumn}, whose broad {@code extraction}
             * slot holds the {@link CallSiteExtraction.NestedInputField}) for UPDATE / DELETE, and
             * recomputed at emit from the {@code fields()} envelope for INSERT VALUES. Reusing
             * {@code List<LookupKeyField>} as a path-bearing flat view would be only half-honest: the
             * composite arms narrow their {@code extraction} to {@code NodeIdDecodeKeys} and cannot
             * carry a {@code NestedInputField}, so the path is parked on the truthful carriers instead.
             */
            public static TableInputArg of(
                String name,
                String typeName,
                boolean nonNull,
                boolean list,
                TableRef inputTable,
                List<InputColumnBindingGroup> fieldBindings,
                Optional<ArgConditionRef> argCondition,
                List<InputField> fields
            ) {
                var lookupKeyFields = fields.stream()
                    .filter(f -> f instanceof InputField.LookupKeyField)
                    .map(f -> (InputField.LookupKeyField) f)
                    .toList();
                List<InputField.SetField> setFields = List.of();
                return new TableInputArg(
                    name, typeName, nonNull, list, inputTable, fieldBindings,
                    argCondition, fields, lookupKeyFields, setFields);
            }
        }

        /**
         * Input type without {@code @table}. Resolved against the surrounding query field's
         * target table by {@link InputFieldResolver}: every classified field contributes its
         * implicit / explicit predicates against that table. Any
         * unresolvable field rejects the surrounding argument as
         * {@link no.sikt.graphitron.rewrite.ArgumentRef.UnclassifiedArg} carrying a typed
         * {@link Rejection}.
         */
        record PlainInputArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            Optional<ArgConditionRef> argCondition,
            List<InputField> fields
        ) implements InputTypeArg {}
    }

    /**
     * Argument carrying {@code @orderBy}. {@code sortFieldName} / {@code directionFieldName}
     * name the fields on the input enum/type the projector reads.
     */
    record OrderByArg(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        String sortFieldName,
        String directionFieldName
    ) implements ArgumentRef {}

    /**
     * One of the four Relay pagination arguments. The {@link Role} identifies which.
     */
    record PaginationArgRef(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        Role role
    ) implements ArgumentRef {

        /** Which Relay pagination argument this ref corresponds to. */
        enum Role { FIRST, LAST, AFTER, BEFORE }
    }

    /**
     * Argument that could not be classified into any other variant; surfaces as a validation
     * error. Carries a typed {@link Rejection} so structured payloads (e.g.
     * {@link Rejection.AuthorError.UnknownName} from {@link InputFieldResolver}) ride through
     * to {@code UnclassifiedField.rejection} without collapsing to a prose-only form.
     */
    record UnclassifiedArg(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        Rejection rejection
    ) implements ArgumentRef {
        /** Prose accessor: renders the typed {@link #rejection}. */
        public String reason() { return rejection.message(); }
    }
}
