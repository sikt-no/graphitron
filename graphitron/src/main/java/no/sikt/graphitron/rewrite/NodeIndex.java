package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * R317 — pure, fixed-point reverse index over the schema's {@code @node} types. Built once by
 * {@link TypeBuilder#buildClassificationIndices} by directive-scanning every SDL type (a superset of
 * the reachable set, unpruned) for {@code @node}+{@code @table} objects through the same producer
 * classification uses ({@code buildTableType}) plus the catalog (the inputs {@link NodeType} itself
 * comes from), and read by field classification in place of any whole-registry scan or keyed
 * {@code ctx.types} lookup, so the field pass carries no dependency on a populated type registry for
 * node resolution.
 *
 * <p>R317 slice 3d — the index is <b>pure</b>: it carries no classification duty (no demotion, no
 * reachability prune, and no typeId-uniqueness exclusion). {@code validateNodeTypeIdUniqueness} is
 * the sole owner of typeId uniqueness, as a validation reduction over the registry; a typeId-collided
 * node therefore still appears in this index, and a lookup that resolves one is sound because the
 * collision fails the build at the validation pass before generation. The superset needs no
 * reachability pruning because a {@code @node} self-seeds reachability, so the index and the pruned
 * registry agree on the consulted domain.
 *
 * <p>Two views over the {@code @node} types that classify:
 *
 * <ul>
 *   <li>{@link #forTable} — by backing-table SQL name. A table may back several {@code @node} types
 *       (distinct node ids over the same rows), so this is a one-to-many view: it returns every
 *       node on the table, in registration order. The implicit "encoder for this table"
 *       form (a bare {@code ID} return / an {@code @nodeId}-less carrier) is well-defined only when
 *       the list has exactly one entry; the call site rejects the zero and ambiguous cases, and a
 *       carrier disambiguates a multi-node table via {@code @nodeId(typeName:)} (the {@link #forName}
 *       view).</li>
 *   <li>{@link #forName} — by GraphQL type name, serving the explicit {@code @nodeId(typeName:)}
 *       lookup; each node on a shared table resolves independently here.</li>
 * </ul>
 */
record NodeIndex(Map<String, List<NodeType>> byTable, Map<String, NodeType> byName) {

    static final NodeIndex EMPTY = new NodeIndex(Map.of(), Map.of());

    NodeIndex {
        byTable = Map.copyOf(byTable);
        byName = Map.copyOf(byName);
    }

    /**
     * Every {@link NodeType} backed by the table with this SQL name, in registration order; empty
     * when no {@code @node} covers the table. The caller resolves the implicit encoder only when the
     * list is a singleton (zero and multiple are use-site rejections).
     *
     * <p>The key is case-folded: {@link TypeBuilder#buildClassificationIndices} lowercases the
     * {@code @table(name:)} echo on construction and this lookup lowercases its argument, so a
     * consumer never re-establishes the case-insensitive {@code TableRef.sameTable} contract. A
     * caller passing a catalog-cased or mixed-case table name resolves the same node as one passing
     * the lowercased echo.
     */
    List<NodeType> forTable(String tableSqlName) {
        return byTable.getOrDefault(tableSqlName.toLowerCase(Locale.ROOT), List.of());
    }

    /** The {@link NodeType} with this GraphQL type name, if it classified as one. */
    Optional<NodeType> forName(String typeName) {
        return Optional.ofNullable(byName.get(typeName));
    }
}
