package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * One {@code @key} alternative on an {@link EntityResolution}.
 *
 * <p>{@code requiredFields} is the list of field names a representation must carry to match
 * this alternative (e.g. {@code ["id"]} for {@code @key(fields: "id")},
 * {@code ["tenantId", "sku"]} for {@code @key(fields: "tenantId sku")}).
 *
 * <p>{@code columns} maps to the jOOQ columns the runtime dispatcher uses for the
 * {@code VALUES (idx, col1, col2, ...)} derived-table SELECT. For {@link KeyShape#DIRECT}
 * alternatives, {@code requiredFields.size() == columns.size()} and the rep's field values
 * map index-by-index to column values. For {@link KeyShape#NODE_ID} alternatives,
 * {@code requiredFields == ["id"]} and the rep's id is decoded through {@code NodeIdEncoder}
 * to recover the column values.
 *
 * <p>{@code resolvable} mirrors the federation-spec {@code resolvable} argument
 * ({@code @key(fields:, resolvable: Boolean = true)}). When {@code false} the dispatcher must
 * skip this alternative during matching: the subgraph declares the key for reference-only and
 * federation surfaces its own resolution-failure error.
 */
public record KeyAlternative(
    List<String> requiredFields,
    List<ColumnRef> columns,
    boolean resolvable,
    KeyShape shape
) {

    /**
     * Distinguishes consumer-declared {@code @key} (column-value path) from {@code @node}
     * (NodeId-decode path). The dispatcher's SELECT shape is otherwise identical: both go
     * through the same {@code VALUES (idx, col1, col2, ...)} derived-table emitter.
     */
    public enum KeyShape {
        /**
         * {@code requiredFields.size() == columns.size()}; rep field values map index-by-index
         * to column values. Consumer-declared {@code @key} path.
         */
        DIRECT,
        /**
         * {@code requiredFields == ["id"]}; the rep's id is a base64 NodeId decoded by
         * {@code NodeIdEncoder} into the columns list. {@code @node} path (whether synthesised
         * or carried by an explicit {@code @key(fields: "id")} on a {@code @node} type).
         */
        NODE_ID
    }
}
