package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_SERVED_BY_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_SERVED_BY_ID_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The nodes this subgraph fetches by their id: writes {@code graphitron_node_served_by_id} from the
 * rule view beside it, marked and swept. A node is served by id when it is resolvable and its own
 * {@code id} binds the node id at its table, which {@link OccurrenceBindings} states; a stub another
 * subgraph owns, and a node whose {@code id} carries {@code @field}, are not.
 *
 * <p>Runs as a stage of the graphitron gatherer after the stage writing the occurrence bindings it
 * reads. The node lookup and the entity dispatch both read the relation, so neither states the
 * predicate itself.
 */
public final class NodesServedById {

    private NodesServedById() {}

    /** Writes the graph's nodes served by id marked with {@code touchedAt}, and sweeps the rest. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var t = GRAPHITRON_NODE_SERVED_BY_ID;
        var r = GRAPHITRON_NODE_SERVED_BY_ID_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.TYPE_NAME, r.TABLE_SOURCE_NAME, r.TABLE_SCHEMA, r.TABLE_NAME,
                    val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(t.GRAPH_NAME, t.TYPE_NAME)
            .doUpdate()
            .set(t.TABLE_SOURCE_NAME, excluded(t.TABLE_SOURCE_NAME))
            .set(t.TABLE_SCHEMA, excluded(t.TABLE_SCHEMA))
            .set(t.TABLE_NAME, excluded(t.TABLE_NAME))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
