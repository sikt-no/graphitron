package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import no.sikt.graphitron.model.grammar.NodeDeclaration;

/**
 * Pure, fixed-point reverse index over the schema's node types. Built once by
 * {@link TypeBuilder#buildClassificationIndices} over every SDL type (a superset of the pruned
 * registry) and read by field classification, which therefore carries no dependency on a populated
 * type registry for node resolution. Membership follows the classified verdict, not a directive
 * scan, so a node inferred from {@code implements Node} plus catalog metadata is indexed on the same
 * terms as a declared {@code @node}.
 *
 * <p>The index is <b>pure</b>: it carries no classification duty (no demotion, no reachability
 * prune, no typeId-uniqueness exclusion). {@code validateNodeTypeIdUniqueness} is the sole owner of
 * typeId uniqueness, so a typeId-collided node still appears here; a lookup that resolves one is
 * sound because the collision fails the build at validation, before generation. The superset needs
 * no reachability pruning because a node self-seeds reachability, so the index and the pruned
 * registry agree on the consulted domain. That seeding is load-bearing for the uniqueness reduction,
 * which iterates the pruned registry: {@link SchemaReachability} therefore seeds on
 * {@link NodeDeclaration}, the same predicate the classifier's promotion gate uses, rather than on
 * {@code @node} presence.
 *
 * <p>Two views: {@link #forTable} by backing-table SQL name (one-to-many; a table may back several
 * node types over the same rows) and {@link #forName} by GraphQL type name, serving the explicit
 * {@code @nodeId(typeName:)} lookup.
 */
record NodeIndex(Map<String, List<NodeType>> byTable, Map<String, NodeType> byName) {

    static final NodeIndex EMPTY = new NodeIndex(Map.of(), Map.of());

    NodeIndex {
        byTable = Map.copyOf(byTable);
        byName = Map.copyOf(byName);
    }

    /**
     * Every {@link NodeType} backed by the table with this SQL name, in registration order; empty
     * when no node type covers the table. The caller resolves the implicit encoder only when the
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
