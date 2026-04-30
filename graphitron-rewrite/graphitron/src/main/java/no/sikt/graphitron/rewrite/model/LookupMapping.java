package no.sikt.graphitron.rewrite.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Generation-ready mapping for a lookup field. After R50 phase (f), the standard
 * VALUES + JOIN derived-table path is the only shape that survives; the legacy
 * {@code NodeIdMapping} arm carries the still-active single-key NodeId-as-lookup-key path
 * until phase (f-C) folds it onto a {@code ColumnMapping} carrying a
 * {@link ColumnMapping.LookupArg.ScalarLookupArg} (single-key) or
 * {@link ColumnMapping.LookupArg.DecodedRecord} (composite-key) with
 * {@link CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch}.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link ColumnMapping} — the standard VALUES + JOIN derived-table path. Preserves input
 *       ordering via the {@code idx} column. Args are sealed on the source-shape axis;
 *       arity is the sum of slots across args.</li>
 *   <li>{@link NodeIdMapping} — single composite node-ID argument; skips VALUES + JOIN and
 *       emits a {@code NodeIdEncoder.hasIds} / {@code hasId} WHERE predicate instead.
 *       Retires in R50 phase (f-C).</li>
 * </ul>
 */
public sealed interface LookupMapping permits LookupMapping.ColumnMapping, LookupMapping.NodeIdMapping {

    /** The jOOQ table that the lookup binds against. */
    TableRef targetTable();

    /**
     * Standard column-based lookup mapping. Represents the N × M positional contract described in
     * {@code docs/code-generation-triggers.md}: given M input rows (each row being a tuple of the
     * declared lookup keys), the field returns N results per input row, preserving input order.
     *
     * <p>The generator materialises this as a {@code VALUES(idx, col1, col2, …)} derived table
     * joined against the target table by equality on each key column, ordered by {@code input.idx}
     * to preserve input ordering.
     *
     * <p>{@code args} carries each top-level GraphQL argument that contributes one or more lookup
     * key slots, sealed on the source-shape axis: {@link LookupArg.ScalarLookupArg} for one slot,
     * {@link LookupArg.MapInput} for a composite-key Map-shaped input, {@link LookupArg.DecodedRecord}
     * for a composite-PK NodeId where decode runs once per row at the arg layer. Slot order across
     * args is declaration order: scalar args contribute one slot each; composite args contribute
     * one slot per binding in binding order.
     */
    record ColumnMapping(
        List<LookupArg> args,
        TableRef targetTable
    ) implements LookupMapping {

        public ColumnMapping {
            args = List.copyOf(args);
        }

        /**
         * Flat list of target columns in slot order: one per {@link LookupArg.ScalarLookupArg},
         * one per binding for {@link LookupArg.MapInput} / {@link LookupArg.DecodedRecord}.
         * The size equals the {@code Row<N+1>} arity (minus the {@code idx} cell). Used by
         * the VALUES + JOIN emitter for typed-row construction, alias labels, and the
         * USING / ON column list.
         */
        public List<ColumnRef> slotColumns() {
            var cols = new ArrayList<ColumnRef>();
            for (var a : args) {
                switch (a) {
                    case LookupArg.ScalarLookupArg s -> cols.add(s.targetColumn());
                    case LookupArg.MapInput m -> {
                        for (var b : m.bindings()) cols.add(b.targetColumn());
                    }
                    case LookupArg.DecodedRecord d -> {
                        for (var b : d.bindings()) cols.add(b.targetColumn());
                    }
                }
            }
            return List.copyOf(cols);
        }

        /** {@code true} when any arg is list-typed (drives the row-count loop in the emitter). */
        public boolean hasListArg() {
            return args.stream().anyMatch(LookupArg::list);
        }

        /**
         * One top-level GraphQL argument's contribution to the lookup. Sealed on the
         * source-shape axis: scalar (one slot), Map-keyed composite (N slots), or decoded
         * jOOQ Record (N slots from a per-NodeType {@code decode<TypeName>} helper).
         *
         * <p>Structural homogeneity is type-enforced rather than validator-asserted:
         * a {@code MapInput}'s bindings are all {@link InputColumnBinding.MapBinding};
         * a {@code DecodedRecord}'s bindings are all {@link InputColumnBinding.RecordBinding}.
         */
        public sealed interface LookupArg permits LookupArg.ScalarLookupArg, LookupArg.MapInput, LookupArg.DecodedRecord {

            /** The top-level GraphQL argument name. */
            String argName();

            /**
             * Outer argument's list cardinality. Determines whether the emitter unrolls the local
             * with {@code .get(i)} per row index or broadcasts the same value across all rows.
             */
            boolean list();

            /**
             * Single-slot arg: the value reaches one lookup column directly.
             *
             * <p>{@code extraction} is {@link CallSiteExtraction.Direct} for non-NodeId args,
             * {@link CallSiteExtraction.JooqConvert} when the column type needs jOOQ Convert,
             * or {@link CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch} for the post-R50
             * NodeId-as-lookup-key fold (single-key target).
             */
            record ScalarLookupArg(
                String argName,
                ColumnRef targetColumn,
                CallSiteExtraction extraction,
                boolean list
            ) implements LookupArg {}

            /** Composite-key Map-shaped input (R5's {@code @lookupKey} on input-object fields). */
            record MapInput(
                String argName,
                boolean list,
                List<InputColumnBinding.MapBinding> bindings
            ) implements LookupArg {

                public MapInput {
                    bindings = List.copyOf(bindings);
                }
            }

            /**
             * Composite-PK NodeId arg. The per-NodeType {@code decode<TypeName>} helper runs
             * once per input row at the arg layer (producing a {@code Record<N>}); bindings
             * index the Record positionally. {@code extraction} on the bindings is structurally
             * always {@code Direct}, so {@link InputColumnBinding.RecordBinding} omits it.
             *
             * <p>Failure-mode is {@link CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch}:
             * a {@code null} return on a lookup-key arg is an authored-input contract violation
             * and surfaces as a {@code GraphqlErrorException}.
             */
            record DecodedRecord(
                String argName,
                boolean list,
                HelperRef.Decode decodeMethod,
                List<InputColumnBinding.RecordBinding> bindings
            ) implements LookupArg {

                public DecodedRecord {
                    bindings = List.copyOf(bindings);
                }
            }
        }
    }

    /**
     * Node-ID-based lookup mapping. The lookup key is a single base64-encoded composite node ID
     * argument (or a list of them); the generator emits a {@code NodeIdStrategy.hasIds} /
     * {@code hasId} WHERE predicate instead of a VALUES + JOIN derived table.
     *
     * <p>Retires in R50 phase (f-C); single-key NodeId folds onto
     * {@link ColumnMapping.LookupArg.ScalarLookupArg} with
     * {@link CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch}, composite-PK NodeId folds onto
     * {@link ColumnMapping.LookupArg.DecodedRecord}.
     */
    record NodeIdMapping(
        String argName,
        String nodeTypeId,
        List<ColumnRef> nodeKeyColumns,
        boolean list,
        TableRef targetTable
    ) implements LookupMapping {}
}
