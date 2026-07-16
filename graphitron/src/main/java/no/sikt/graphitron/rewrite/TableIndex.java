package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;

import java.util.Map;
import java.util.Optional;

/**
 * Pure, typename-keyed fixed-point index over the schema's table-backed types
 * ({@link TableBackedType}: {@code @table} objects and {@code @table}+{@code @discriminate}
 * interfaces). Built once by {@link TypeBuilder#buildClassificationIndices} by directive-scanning
 * every SDL type (a superset of the reachable set, unpruned) through the same producers
 * classification uses ({@code buildTableType} / {@code buildTableInterfaceType}); read by field
 * classification in place of a keyed {@code ctx.types} lookup, so the field pass carries no
 * dependency on a populated type registry for the table-backed fact.
 *
 * <p>The index is pure: it carries no classification duty (no demotion, no reachability prune, no
 * uniqueness reduction). The stored value is the produced verdict (a {@link TableBackedType}), so a
 * caller can still refine with {@code instanceof TableInterfaceType} and read {@code .table()}.
 *
 * <p>The superset needs no reachability pruning because every type a read actually queries is
 * already reachable: a {@code @table} data field on a payload is queried only by a field that
 * reaches it, so the index and the pruned registry agree on the consulted domain.
 */
record TableIndex(Map<String, TableBackedType> byName) {

    static final TableIndex EMPTY = new TableIndex(Map.of());

    TableIndex {
        byName = Map.copyOf(byName);
    }

    /** The {@link TableBackedType} with this GraphQL type name, if it classified as one. */
    Optional<TableBackedType> forName(String typeName) {
        return Optional.ofNullable(byName.get(typeName));
    }
}
