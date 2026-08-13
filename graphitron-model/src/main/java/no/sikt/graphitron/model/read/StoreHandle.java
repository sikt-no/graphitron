package no.sikt.graphitron.model.read;

import org.jooq.DSLContext;

import java.util.Objects;

/**
 * A query surface over the store plus the graph whose partition a read may see. Every store read
 * takes this shape rather than a bare {@link DSLContext}, and that is the point: a persisted store
 * spans every module of a workspace, so a query holding only a {@code DSLContext} has no way to
 * say which module's rows it meant, and one that forgets to scope answers from a sibling's.
 *
 * <p>Scoping through the pair makes the constraint structural instead of a rule each query site
 * remembers. The graph-keyed families lead with {@code graph_name} and can be filtered directly;
 * the source-keyed ones ({@code sql_}, {@code jvm_}) join to {@code store_graph_source} to reach
 * the graph, which is why {@link SourceGraph} resolves in this direction too.
 *
 * <p>One type for every consumer, not one per module. A second copy of this record, however
 * faithful, is how the scoping stops being structural: two handles are two conventions, and
 * nothing then makes a query site take the one its module's rows were written under.
 *
 * <p>The handle owns nothing. The {@code DSLContext} belongs to whoever opened the store (a
 * {@code GraphitronModelStore}, or a reader it minted), and closing it is that owner's business;
 * a handle is a pair of values that outlives no connection and closes none.
 *
 * @param dsl the query surface, over either the store's own connection or a reader's
 * @param graphName the graph whose partition reads through this handle are confined to
 */
public record StoreHandle(DSLContext dsl, String graphName) {

    public StoreHandle {
        Objects.requireNonNull(dsl, "dsl");
        Objects.requireNonNull(graphName, "graphName");
    }
}
