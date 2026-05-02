package no.sikt.graphitron.rewrite.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Generation-ready mapping for a lookup field. After R50 phase (f-D), the only shape is
 * {@link ColumnMapping} — the standard VALUES + JOIN derived-table path. Legacy
 * {@code NodeIdMapping} retired alongside the {@code NodeIdEncoder.hasIds} /
 * {@code hasId} predicate; lookup-key NodeId args fold onto
 * {@link ColumnMapping.LookupArg.ScalarLookupArg} (single-key NodeType) or
 * {@link ColumnMapping.LookupArg.DecodedRecord} (composite-key NodeType, lands in phase g)
 * carrying a {@link CallSiteExtraction.NodeIdDecodeKeys} arm (Throw on synthesised lookup-key
 * paths, Skip on the same-table {@code @nodeId} filter path).
 *
 * <p>The sealed interface is retained for shape-locality (every {@link LookupField} carries
 * a {@code LookupMapping}); a future "rooted at parent via correlated subquery" variant from
 * R24 would land as a sibling permit.
 */
public sealed interface LookupMapping permits LookupMapping.ColumnMapping {

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
             * <p>Failure-mode lives on the carrier as
             * {@link CallSiteExtraction.NodeIdDecodeKeys}:
             * {@link CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch ThrowOnMismatch} on
             * synthesised lookup-key paths (a wrong-type id is an authored-input contract violation),
             * {@link CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement SkipMismatchedElement}
             * on the same-table {@code @nodeId} filter path (a malformed id drops silently to
             * "no row matches" instead of a 500). The emitter branches on the arm at the per-row
             * decode site.
             */
            record DecodedRecord(
                String argName,
                boolean list,
                CallSiteExtraction.NodeIdDecodeKeys extraction,
                List<InputColumnBinding.RecordBinding> bindings
            ) implements LookupArg {

                public DecodedRecord {
                    bindings = List.copyOf(bindings);
                }
            }
        }
    }

}
