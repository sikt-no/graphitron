package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.JoinStep;
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
 *       arg with a resolved single-hop {@code joinPath} (R40, single-key target NodeType).</li>
 *   <li>{@link ScalarArg.CompositeColumnReferenceArg} — FK-target {@code @nodeId(typeName: T)}
 *       scalar arg whose target NodeType has multiple key columns (R40, arity ≥ 2).</li>
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
         * <p>Conceptually mirrors {@link no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField}
         * on the argument side; {@code column} is the target NodeType's key column. Diverges on
         * the join shape: the input-field carrier declares {@code List<JoinStep> joinPath}
         * (single-hop today, broader type pre-dates R40); the arg-side carrier narrows to
         * {@link JoinStep.FkJoin} directly because R40 only ships the simple direct-FK case
         * (single-hop, FK targetColumns positionally match NodeType keyColumns) — the
         * pathological case is rejected at classify time and the JOIN-with-translation shape
         * is deferred to R57. The body emitter pairs the carrier with
         * {@link no.sikt.graphitron.rewrite.model.BodyParam.Eq} (scalar) or
         * {@link no.sikt.graphitron.rewrite.model.BodyParam.In} (list) against
         * {@code fkJoin.sourceColumns()} on the field's own containing table.
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
            JoinStep.FkJoin fkJoin,
            CallSiteExtraction.NodeIdDecodeKeys extraction,
            Optional<ArgConditionRef> argCondition,
            boolean suppressedByFieldOverride
        ) implements ScalarArg {

            public ColumnReferenceArg {
                requireNonNull(fkJoin, "fkJoin");
            }
        }

        /**
         * FK-target {@code @nodeId(typeName: T)} scalar arg whose target NodeType has multiple
         * key columns. Conceptual mirror of
         * {@link no.sikt.graphitron.rewrite.model.InputField.CompositeColumnReferenceField} with
         * the same join-shape narrowing as {@link ColumnReferenceArg}: {@code fkJoin} is the
         * single-hop FK directly (not {@code List<JoinStep>}). {@code columns.size()} must be
         * ≥ 2; arity-1 cases route to {@link ColumnReferenceArg}.
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
            JoinStep.FkJoin fkJoin,
            CallSiteExtraction.NodeIdDecodeKeys extraction,
            Optional<ArgConditionRef> argCondition,
            boolean suppressedByFieldOverride
        ) implements ScalarArg {

            public CompositeColumnReferenceArg {
                requireNonNull(columns, "columns");
                requireNonNull(fkJoin, "fkJoin");
                if (columns.size() < 2) {
                    throw new IllegalArgumentException(
                        "CompositeColumnReferenceArg requires arity >= 2; arity-1 routes to ScalarArg.ColumnReferenceArg");
                }
                columns = List.copyOf(columns);
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
         * Used by composite-key lookups and (eventually) by mutations.
         */
        record TableInputArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            TableRef inputTable,
            List<InputColumnBinding.MapBinding> fieldBindings,
            Optional<ArgConditionRef> argCondition,
            List<InputField> fields
        ) implements InputTypeArg {}

        /**
         * Input type without {@code @table}. Currently silently skipped unless paired with
         * {@code @condition}; in that case {@code argCondition} projects to a
         * {@code ConditionFilter}. See {@code docs/argument-resolution.md} out-of-scope note.
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

    /** Argument that could not be classified into any other variant — surfaces as a validation error. */
    record UnclassifiedArg(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        String reason
    ) implements ArgumentRef {}
}
