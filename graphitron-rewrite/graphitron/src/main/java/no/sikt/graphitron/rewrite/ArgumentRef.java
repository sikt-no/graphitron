package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.TableRef;

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
         * Scalar arg resolved to a composite node ID. Fires when the arg GraphQL type is
         * {@code ID}, the target table is a {@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType},
         * and the arg does not carry {@code @nodeId(typeName:)} (that is the reference variant,
         * handled in Step 5). Both the synthesized route (table has KjerneJooqGenerator metadata)
         * and the declared route ({@code @nodeId} on the arg) produce this variant.
         *
         * <p>{@code nodeTypeId} and {@code nodeKeyColumns} are taken verbatim from the NodeType's
         * metadata. {@code extraction} is {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct}
         * for scalar and list top-level args. {@code isLookupKey} reflects {@code @lookupKey} at
         * classify time — lookup-key {@code NodeIdArg}s project into
         * {@link no.sikt.graphitron.rewrite.model.LookupMapping.NodeIdMapping} and are emitted
         * via {@code NodeIdStrategy.hasIds}; non-lookup ones contribute a WHERE predicate.
         */
        record NodeIdArg(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            String nodeTypeId,
            List<ColumnRef> nodeKeyColumns,
            CallSiteExtraction extraction,
            Optional<ArgConditionRef> argCondition,
            boolean isLookupKey
        ) implements ScalarArg {}

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
