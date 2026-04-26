package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Generation-ready mapping for a lookup field: either a set of key columns (the standard
 * VALUES + JOIN path) or a node-id composite key (the {@code hasIds} path).
 * Carried on every {@link LookupField} variant.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link ColumnMapping} — the standard N-column VALUES + JOIN derived-table path.
 *       Preserves input ordering via the {@code idx} column.</li>
 *   <li>{@link NodeIdMapping} — single composite node-ID argument; skips VALUES + JOIN and
 *       emits a {@code NodeIdStrategy.hasIds} / {@code hasId} WHERE predicate instead.
 *       Input-order preservation does not apply (set membership check, not positional join).</li>
 * </ul>
 */
public sealed interface LookupMapping permits LookupMapping.ColumnMapping, LookupMapping.NodeIdMapping {

    /** The jOOQ table that the lookup binds against. */
    TableRef targetTable();

    /**
     * Standard column-based lookup mapping. Represents the N × M positional contract described in
     * {@code docs/code-generation-triggers.md}: given M input rows (each row being a tuple of the
     * declared lookup keys), the field returns N results per input row — preserving input order.
     *
     * <p>The generator materialises this as a {@code VALUES(idx, col1, col2, …)} derived table
     * joined against the target table by equality on each key column, ordered by {@code input.idx}
     * to preserve input ordering. See {@code docs/argument-resolution.md#lookupmapping}
     * and the VALUES + JOIN builder in {@code GeneratorUtils}.
     *
     * <p>{@code columns} carries the scalar keys declared via {@code @lookupKey} on arguments and on
     * composite-key input-type fields (argres Phase 3), in declaration order. Each column's
     * {@link LookupColumn#sourcePath} records how to reach the value from the top-level argument.
     */
    record ColumnMapping(
        List<LookupColumn> columns,
        TableRef targetTable
    ) implements LookupMapping {

        /**
         * One column in the lookup key tuple.
         *
         * <p>{@code sourcePath} names the route from the top-level argument to this column's value:
         * a single-segment path {@code [argName]} for a scalar {@code @lookupKey} argument, or a
         * two-segment path {@code [argName, inputFieldName]} for a composite-key field reached
         * through a {@code @table}-annotated input type.
         * {@code targetColumn} is the column on {@link ColumnMapping#targetTable} that the JOIN
         * equates against.
         * {@code extraction} tells the generator how to read the value from
         * {@code DataFetchingEnvironment} at the call site.
         * {@code list} is {@code true} when the argument (or its list cardinality) contributes
         * multiple VALUES rows; {@code false} when the value is broadcast as a scalar across all rows.
         * Within a composite path, {@code list} inherits from the outer argument — individual input
         * fields are always scalar.
         */
        public record LookupColumn(
            SourcePath sourcePath,
            ColumnRef targetColumn,
            CallSiteExtraction extraction,
            boolean list
        ) {

            /** The top-level GraphQL argument name — first (and, for scalar paths, only) segment. */
            public String argName() {
                return sourcePath.segments().get(0);
            }

            /** {@code true} when this column lives on a composite-key input type (path length &gt; 1). */
            public boolean isComposite() {
                return sourcePath.segments().size() > 1;
            }
        }

        /**
         * Path from the top-level argument to a single lookup-key value.
         *
         * <p>Length 1 for scalar {@code @lookupKey} arguments; length 2 for an input-type field
         * reached through a {@code @table}-annotated argument (argres Phase 3). Deeper nesting is
         * reserved for future phases.
         */
        public record SourcePath(List<String> segments) {
            public SourcePath {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("SourcePath must have at least one segment");
                }
                segments = List.copyOf(segments);
            }
        }
    }

    /**
     * Node-ID-based lookup mapping. The lookup key is a single base64-encoded composite node ID
     * argument (or a list of them); the generator emits a {@code NodeIdStrategy.hasIds} /
     * {@code hasId} WHERE predicate instead of a VALUES + JOIN derived table.
     *
     * <p>{@code argName} is the GraphQL argument name (e.g. {@code "id"}) used to call
     * {@code env.getArgument(argName)} at the fetcher call site.
     * {@code nodeTypeId} and {@code nodeKeyColumns} are passed verbatim to
     * {@code NodeIdStrategy.hasIds(typeId, ids, keyColumns)}.
     * {@code list} mirrors the argument's list cardinality — determines whether the emitter calls
     * {@code hasIds} (list) or {@code hasId} (scalar) and whether the return type is a list.
     */
    record NodeIdMapping(
        String argName,
        String nodeTypeId,
        List<ColumnRef> nodeKeyColumns,
        boolean list,
        TableRef targetTable
    ) implements LookupMapping {}
}
