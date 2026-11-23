package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE_SESSION;

/**
 * The table-bound types whose projection reads the session, as the store states them: each one's
 * {@code $project} takes the statement's {@code dsl} after its environment, so a lift selecting one
 * hands it over.
 */
public final class ProjectionSessionQuery {

    private ProjectionSessionQuery() {}

    /** The graph's types whose projection reads the session, by type name. */
    public static Set<String> read(StoreHandle store) {
        var s = GRAPHITRON_TABLETYPE_SESSION;
        return Set.copyOf(store.dsl().select(s.TYPE_NAME)
            .from(s)
            .where(s.GRAPH_NAME.eq(store.graphName()))
            .fetch(s.TYPE_NAME));
    }
}
