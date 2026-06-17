package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * R317 — fixed-point reverse index over the schema's {@code @node} types. Built once by
 * {@link TypeBuilder#buildClassificationIndices} from the reachable {@code @node} SDL scan plus the
 * catalog (the inputs {@link NodeType} itself comes from), and read by field classification in
 * place of any whole-registry scan or keyed {@code ctx.types} lookup, so the field pass carries no
 * dependency on a populated type registry for node resolution.
 *
 * <p>Two views over one survivor set, the reachable {@code @node} types that classify minus the
 * typeId-collision groups {@code validateNodeTypeIdUniqueness} demotes (so a lookup never resolves
 * an encoder the registry rejected):
 *
 * <ul>
 *   <li>{@link #forTable} — by backing-table SQL name. A table may back several {@code @node} types
 *       (distinct node ids over the same rows), so this is a one-to-many view: it returns every
 *       node on the table, in reachable-registration order. The implicit "encoder for this table"
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
     * Every surviving {@link NodeType} backed by the table with this SQL name, in
     * reachable-registration order; empty when no {@code @node} covers the table. The caller
     * resolves the implicit encoder only when the list is a singleton (zero and multiple are
     * use-site rejections).
     */
    List<NodeType> forTable(String tableSqlName) {
        return byTable.getOrDefault(tableSqlName, List.of());
    }

    /** The surviving {@link NodeType} with this GraphQL type name, if it classified as one. */
    Optional<NodeType> forName(String typeName) {
        return Optional.ofNullable(byName.get(typeName));
    }
}
