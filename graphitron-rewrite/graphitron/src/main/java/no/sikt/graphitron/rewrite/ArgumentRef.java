package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.Set;

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
 * can read its {@code inputTable}, {@code fieldBindings}, and {@code fields} directly. This is
 * the only argument-classification type that crosses the model/generator boundary today.
 *
 * <p>See {@code docs/argument-resolution.md} for the design and projection semantics.
 *
 * <h2>Variants</h2>
 * <ul>
 *   <li>{@link ScalarArg.ColumnArg} — scalar arg bound to a jOOQ column.</li>
 *   <li>{@link ScalarArg.CompositeColumnArg} — scalar arg bound to multiple jOOQ columns
 *       through a single per-row decode (composite-PK NodeId carrier; arity ≥ 2).</li>
 *   <li>{@link ScalarArg.ColumnReferenceArg} — FK-target {@code @nodeId(typeName: T)} scalar
 *       arg with a resolved single-hop {@code joinPath} (single-key target NodeType).</li>
 *   <li>{@link ScalarArg.CompositeColumnReferenceArg} — FK-target {@code @nodeId(typeName: T)}
 *       scalar arg whose target NodeType has multiple key columns (arity &ge; 2).</li>
 *   <li>{@link ScalarArg.UnboundArg} — scalar arg whose column could not be resolved;
 *       surfaced as a validation error.</li>
 *   <li>{@link InputTypeArg.TableInputArg} — {@code @table}-backed input type; carries per-field
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
         * Scalar arg resolved to a jOOQ column. {@code argCondition} and
         * {@code suppressedByFieldOverride} drive the four-state projection table; see
         * {@code docs/argument-resolution.md#condition-on-field-and-argument-definitions}.
         * {@code isLookupKey} reflects the presence of {@code @lookupKey} at classify time
         * so projections (notably {@code projectForLookup}) never re-read the SDL directive.
         */
        record ColumnArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            ColumnRef column,
            CallSiteExtraction extraction,
            Optional<ArgConditionRef> argCondition,
            boolean suppressedByFieldOverride,
            boolean isLookupKey
        ) implements ScalarArg {}

        /**
         * Composite-PK NodeId scalar arg: one wire-format base64 id (or list of them) decodes
         * once per row at the arg layer into a {@code Record<N>}; bindings against
         * {@code columns} index the Record positionally. Carrier-side analogue of
         * {@code LookupArg.DecodedRecord} — {@code projectForLookup} lifts it into that
         * shape when {@code isLookupKey} is set.
         *
         * <p>{@code extraction} narrows to {@link CallSiteExtraction.NodeIdDecodeKeys}: the only
         * arms producing a multi-column tuple. Today's classifier only emits
         * {@link CallSiteExtraction.ThrowOnMismatch} (lookup-key path). Mutation-key and
         * top-level filter paths are not yet wired.
         *
         * <p>{@code columns.size()} must be ≥ 2; arity-1 NodeId scalar args route to
         * {@link ColumnArg}.
         */
        record CompositeColumnArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            List<ColumnRef> columns,
            CallSiteExtraction.NodeIdDecodeKeys extraction,
            Optional<ArgConditionRef> argCondition,
            boolean suppressedByFieldOverride,
            boolean isLookupKey
        ) implements ScalarArg {
            public CompositeColumnArg {
                requireNonNull(columns, "columns");
                if (columns.size() < 2) {
                    throw new IllegalArgumentException(
                        "CompositeColumnArg requires arity >= 2; arity-1 routes to ScalarArg.ColumnArg");
                }
                columns = List.copyOf(columns);
            }
        }

        /**
         * FK-target {@code @nodeId(typeName: T)} scalar arg: the target's encoded ids decode into
         * keys of the related NodeType {@code T}, and the predicate filters rows on the FK source
         * column reachable through {@code joinPath}. Single-key (arity-1) target NodeType; the
         * arity ≥ 2 variant is {@link CompositeColumnReferenceArg}.
         *
         * <p>Mirrors {@link no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField}
         * shape-for-shape on the argument side. {@code column} is the target NodeType's key
         * column; {@code joinPath} resolves the single-hop FK from the field's containing table
         * to {@code T.table()}. The body emitter pairs the carrier with
         * {@link no.sikt.graphitron.rewrite.model.BodyParam.Eq} (scalar) or
         * {@link no.sikt.graphitron.rewrite.model.BodyParam.In} (list) against the FK source
         * columns when those columns positionally match the target's NodeType key columns
         * (the simple direct-FK case); pathological cases where they differ are rejected at
         * classify time with a deferred-emission hint (see
         * graphitron-rewrite/roadmap/nodeid-fk-target-arg-join-translation.md).
         *
         * <p>{@code extraction} narrows to {@link CallSiteExtraction.NodeIdDecodeKeys}: input
         * filters are not contract-violation surfaces, so the failure mode is
         * {@code SkipMismatchedElement} (malformed ids drop silently to "no row matches").
         *
         * <p>No {@code isLookupKey} slot: FK-target is a filter, not a lookup. The carrier flows
         * through {@code projectFilters} into the standard {@code GeneratedConditionFilter}
         * pipeline, not {@code LookupMappingResolver}.
         */
        record ColumnReferenceArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            ColumnRef column,
            List<JoinStep> joinPath,
            List<ColumnRef> liftedSourceColumns,
            CallSiteExtraction.NodeIdDecodeKeys extraction,
            Optional<ArgConditionRef> argCondition,
            boolean suppressedByFieldOverride
        ) implements ScalarArg {

            public ColumnReferenceArg {
                joinPath = List.copyOf(joinPath);
                liftedSourceColumns = List.copyOf(liftedSourceColumns);
            }
        }

        /**
         * FK-target {@code @nodeId(typeName: T)} scalar arg whose target NodeType has multiple
         * key columns. Mirrors {@link no.sikt.graphitron.rewrite.model.InputField.CompositeColumnReferenceField}
         * shape-for-shape on the argument side. {@code columns.size()} must be ≥ 2; arity-1
         * cases route to {@link ColumnReferenceArg}.
         *
         * <p>{@code extraction} narrows to {@link CallSiteExtraction.NodeIdDecodeKeys}: the
         * only arm producing a multi-column tuple. Failure mode is
         * {@link CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement} for the same reason
         * documented on {@link ColumnReferenceArg}.
         */
        record CompositeColumnReferenceArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            List<ColumnRef> columns,
            List<JoinStep> joinPath,
            List<ColumnRef> liftedSourceColumns,
            CallSiteExtraction.NodeIdDecodeKeys extraction,
            Optional<ArgConditionRef> argCondition,
            boolean suppressedByFieldOverride
        ) implements ScalarArg {

            public CompositeColumnReferenceArg {
                requireNonNull(columns, "columns");
                if (columns.size() < 2) {
                    throw new IllegalArgumentException(
                        "CompositeColumnReferenceArg requires arity >= 2; arity-1 routes to ScalarArg.ColumnReferenceArg");
                }
                columns = List.copyOf(columns);
                joinPath = List.copyOf(joinPath);
                liftedSourceColumns = List.copyOf(liftedSourceColumns);
            }
        }

        /**
         * Scalar arg whose column could not be resolved on the target table.
         * Step 10 turns this into a validation error (with a candidate hint).
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
         * Input type with {@code @table}; fields resolve to columns on {@code inputTable}.
         * Used by composite-key lookups and by mutations.
         *
         * <p>{@code lookupKeyFields} / {@code setFields} are the typed partition of {@code fields}
         * sourced from {@code DmlKind}-aware logic (R144): on UPDATE, {@code setFields} is exactly
         * the input fields carrying {@code @value} (in SDL declaration order) and
         * {@code lookupKeyFields} is the complement; on DELETE / INSERT, {@code setFields} is empty
         * by classifier guarantee and every admissible input field flows into {@code lookupKeyFields}.
         * Both lists are sealed on {@link InputField.LookupKeyField} / {@link InputField.SetField}
         * respectively (R130 admitted-carrier set: {@code ColumnField},
         * {@code CompositeColumnField}); reference carriers stay outside the permits set. Construct
         * via {@link #of} so the partition has a single derivation path.
         *
         * <p>{@code fieldBindings} is {@code List<InputColumnBindingGroup>}: one group per
         * WHERE-bound input field. {@link InputColumnBindingGroup.MapGroup} for a
         * {@code ColumnField} carrier, {@link InputColumnBindingGroup.DecodedRecordGroup} for a
         * {@code CompositeColumnField} carrier (the composite-PK NodeId case where decode runs
         * once per row at the arg layer into a {@code Record<N>}).
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
             * Factory: partitions {@code fields} into WHERE-side {@code lookupKeyFields} and
             * assignment-side {@code setFields} from the verb {@code kind} and the set of
             * {@code @value}-marked field names (R144).
             *
             * <ul>
             *   <li>UPDATE: {@code setFields} is exactly the {@code @value}-marked admissible
             *       carriers (in SDL declaration order); {@code lookupKeyFields} is the complement
             *       (admissible carriers without {@code @value}).</li>
             *   <li>DELETE / INSERT: {@code setFields} is empty; {@code lookupKeyFields} is every
             *       admissible carrier. INSERT walks {@code fields()} directly for VALUES emit, so
             *       an empty {@code setFields} is correct (audit key
             *       {@code mutation-input.update-set-fields-equal-value-marked}).</li>
             *   <li>UPSERT: refused upstream by {@code MutationInputResolver} under R144; the
             *       factory is unreachable with this kind. Query-side ({@code kind == null}) takes
             *       the same shape as DELETE: every admissible carrier in {@code lookupKeyFields},
             *       empty {@code setFields}.</li>
             * </ul>
             */
            public static TableInputArg of(
                String name,
                String typeName,
                boolean nonNull,
                boolean list,
                TableRef inputTable,
                List<InputColumnBindingGroup> fieldBindings,
                Optional<ArgConditionRef> argCondition,
                List<InputField> fields,
                DmlKind kind,
                Set<String> valueMarkedNames
            ) {
                List<InputField.LookupKeyField> lookupKeyFields;
                List<InputField.SetField> setFields;
                if (kind == DmlKind.UPDATE) {
                    lookupKeyFields = fields.stream()
                        .filter(f -> f instanceof InputField.LookupKeyField)
                        .map(f -> (InputField.LookupKeyField) f)
                        .filter(lk -> !valueMarkedNames.contains(((InputField) lk).name()))
                        .toList();
                    setFields = fields.stream()
                        .filter(f -> f instanceof InputField.SetField)
                        .map(f -> (InputField.SetField) f)
                        .filter(sf -> valueMarkedNames.contains(((InputField) sf).name()))
                        .toList();
                } else {
                    lookupKeyFields = fields.stream()
                        .filter(f -> f instanceof InputField.LookupKeyField)
                        .map(f -> (InputField.LookupKeyField) f)
                        .toList();
                    setFields = List.of();
                }
                return new TableInputArg(
                    name, typeName, nonNull, list, inputTable, fieldBindings,
                    argCondition, fields, lookupKeyFields, setFields);
            }
        }

        /**
         * Input type without {@code @table}. Resolved against the surrounding query field's
         * target table by {@link InputFieldResolver}: every classified field contributes the
         * same implicit / explicit predicates as a {@code @table} input (R205 path B). Any
         * unresolvable field rejects the surrounding argument as
         * {@link no.sikt.graphitron.rewrite.ArgumentRef.UnclassifiedArg} carrying a typed
         * {@link Rejection}, mirroring the {@code @table}-input whole-type rejection at
         * {@link no.sikt.graphitron.rewrite.TypeBuilder#buildTableInputType}.
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
        /** Backwards-compatible prose accessor; renders the typed {@link #rejection}. */
        public String reason() { return rejection.message(); }
    }
}
