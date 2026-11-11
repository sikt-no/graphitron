package no.sikt.graphitron.rewrite.schema.federation;

import no.sikt.graphitron.emit.EntityKeyQuery;
import no.sikt.graphitron.model.derive.StoreNodeTables;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.EntityResolutions;
import no.sikt.graphitron.rewrite.model.KeyAlternative;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Maps the store's entities ({@link EntityKeyQuery}) onto the {@link EntityResolution} and
 * {@link KeyAlternative} records the entity dispatch, the node fetcher and the tenant fold read.
 * Transitional: it goes when the node and entity generators read the store themselves.
 *
 * <p>It decides nothing a key means; that is the query's. It only builds the catalog references from
 * the store's names, through {@link StoreNodeTables} for a table and a node's type id and key columns,
 * and adds the node id alternative a resolvable node carries whether or not a key states it, first,
 * where none of its keys is one: {@code Query.node} resolves a node by its id in a graph with no
 * federation at all. An entity whose node id alternative belongs to a node this subgraph does not
 * resolve, a stub, is left out, as is one whose table the store cannot name a constants class for.
 */
public final class StoreEntityResolutions {

    private StoreEntityResolutions() {}

    /** The graph's entities, read from {@code store}. */
    public static EntityResolutions read(StoreHandle store) {
        var nodes = StoreNodeTables.read(store.dsl(), store.graphName());
        var out = new LinkedHashMap<String, EntityResolution>();
        for (var entity : EntityKeyQuery.read(store)) {
            var table = StoreNodeTables.tableRef(store.dsl(), entity.tableSourceName(), entity.tableSchema(),
                entity.tableName());
            if (table.isEmpty()) {
                continue;
            }
            var node = entity.resolvableNode() ? nodes.get(entity.typeName()) : Optional.<StoreNodeTables.NodeTable>empty();
            var alternatives = new ArrayList<KeyAlternative>();
            boolean dropped = false;
            for (var alternative : entity.alternatives()) {
                switch (alternative) {
                    case EntityKeyQuery.Alternative.Direct direct -> alternatives.add(new KeyAlternative.Direct(
                        direct.fields().stream()
                            .map(f -> new KeyAlternative.RepBinding(f.fieldName(), new ColumnRef(f.columnName(),
                                f.columnJavaName(), f.bindingType(), f.databaseType())))
                            .toList(),
                        direct.resolvable()));
                    case EntityKeyQuery.Alternative.NodeId nodeId -> {
                        if (node.isEmpty()) {
                            dropped = true;
                        } else {
                            alternatives.add(new KeyAlternative.NodeId(node.get().typeId(),
                                node.get().keyColumns(), nodeId.resolvable()));
                        }
                    }
                }
            }
            if (dropped || (alternatives.isEmpty() && node.isEmpty())) {
                continue;
            }
            if (node.isPresent() && alternatives.stream().noneMatch(KeyAlternative.NodeId.class::isInstance)) {
                alternatives.addFirst(new KeyAlternative.NodeId(node.get().typeId(), node.get().keyColumns(), true));
            }
            out.put(entity.typeName(), new EntityResolution(entity.typeName(), table.get(), List.copyOf(alternatives)));
        }
        return new EntityResolutions(out);
    }
}
