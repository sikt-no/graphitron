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
 * {@code VALUES (idx, col1, col2, ...)} derived-table SELECT. Both variants feed the same
 * emitter; they differ only in how the rep is turned into a column-value row.
 *
 * <p>{@code resolvable} mirrors the federation-spec {@code resolvable} argument
 * ({@code @key(fields:, resolvable: Boolean = true)}). When {@code false} the dispatcher must
 * skip this alternative during matching: the subgraph declares the key for reference-only and
 * federation surfaces its own resolution-failure error.
 *
 * <p>The two variants carry structurally different relationships between {@code requiredFields}
 * and {@code columns}. Rather than state those relationships in prose and leave them
 * unenforced (the gap R477 lived in), each is a fact of the variant's structure:
 * {@link Direct} stores {@code (rep field, column)} pairs, so its sizes cannot disagree, and
 * {@link NodeId} derives {@code requiredFields()} as a constant {@code ["id"]} rather than
 * storing it.
 */
public sealed interface KeyAlternative permits KeyAlternative.Direct, KeyAlternative.NodeId {

    List<String> requiredFields();

    List<ColumnRef> columns();

    boolean resolvable();

    /**
     * One {@code (rep field, column)} pairing of a {@link Direct} alternative: the rep's value
     * for {@code repField} binds to {@code column} in the derived-table SELECT.
     */
    record RepBinding(String repField, ColumnRef column) {}

    /**
     * Consumer-declared {@code @key} (column-value path): the rep carries each required field
     * individually and its values map pairwise to column values. Storing the pairing as a
     * {@code List<RepBinding>} makes "sizes equal, index-by-index mapping" unrepresentable
     * rather than a prose promise; {@code requiredFields()} and {@code columns()} unzip the
     * pairs in order.
     */
    record Direct(List<RepBinding> bindings, boolean resolvable) implements KeyAlternative {
        @Override
        public List<String> requiredFields() {
            return bindings.stream().map(RepBinding::repField).toList();
        }

        @Override
        public List<ColumnRef> columns() {
            return bindings.stream().map(RepBinding::column).toList();
        }
    }

    /**
     * {@code @node} path: the rep carries a single {@code id} whose value is a base64 NodeId
     * decoded by {@code NodeIdEncoder} into the {@code columns} list (whether the alternative is
     * synthesised for a {@code @node} type or carried by an explicit {@code @key(fields: "id")}
     * on one). {@code requiredFields()} is the constant {@code ["id"]}, a fact of the variant
     * rather than stored state that could disagree. {@code expectedTypeId} is the resolved wire
     * prefix the dispatcher passes into {@code NodeIdEncoder.decodeValues} (the type's
     * {@code @node(typeId:)} value, defaulted to the type name at classify time); {@code columns}
     * is the decode arity.
     */
    record NodeId(String expectedTypeId, List<ColumnRef> columns, boolean resolvable)
            implements KeyAlternative {
        @Override
        public List<String> requiredFields() {
            return List.of("id");
        }
    }
}
