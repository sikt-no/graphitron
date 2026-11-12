package no.sikt.graphitron.model.read;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;

import java.util.Objects;

import static no.sikt.graphitron.model.Tables.CODE_GRAPH_CLASS;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.selectOne;

/**
 * A query surface over the store plus the graph whose partition a read may see. Every store read
 * takes this shape rather than a bare {@link DSLContext}, and that is the point: a persisted store
 * spans every module of a workspace, so a query holding only a {@code DSLContext} has no way to
 * say which module's rows it meant, and one that forgets to scope answers from a sibling's.
 *
 * <p>Scoping through the pair makes the constraint structural instead of a rule each query site
 * remembers. The graph-keyed families lead with {@code graph_name} and can be filtered directly;
 * the source-keyed ones ({@code sql_}, {@code code_}) join to {@code store_graph_source} to reach
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

    /**
     * The predicate a source-keyed relation is scoped by: this graph read that source. Written as a
     * semi-join over {@code store_graph_source} rather than a join, so a query keeps one row per
     * fact even where a source belongs to several graphs.
     *
     * <p>Handing the predicate out from the handle is what makes the scoping structural for the
     * families that carry no {@code graph_name} of their own. A query over {@code sql_} or
     * {@code code_} that forgets it does not fail; it answers with a sibling module's tables and
     * classes folded in, which reads as a workspace-wide census and is the failure a shared store
     * makes possible. Graph-keyed relations need none of this and filter on their own column.
     *
     * @param sourceName the relation's own {@code source_name} column
     */
    public Condition reads(Field<String> sourceName) {
        return exists(selectOne()
            .from(STORE_GRAPH_SOURCE)
            .where(STORE_GRAPH_SOURCE.GRAPH_NAME.eq(graphName))
            .and(STORE_GRAPH_SOURCE.SOURCE_NAME.eq(sourceName)));
    }

    /**
     * The predicate a {@code code_} row about a named class is scoped by: this graph resolves the
     * class name to that row's entry. Where two entries on the graph's classpath declare one name,
     * {@link #reads} admits both copies; this admits only the one {@code code_graph_class} names, the
     * first in classpath order, which is the copy the generator resolves the name to. A semi-join, as
     * {@link #reads} is, so a query keeps one row per fact.
     *
     * <p>Implies {@link #reads}: a graph resolves a name only to an entry it reads. A reader that
     * wants every entry declaring a name, to name them all, keeps to {@link #reads} instead.
     *
     * @param sourceName the relation's own {@code source_name} column
     * @param className the relation's own {@code class_name} column
     */
    public Condition resolves(Field<String> sourceName, Field<String> className) {
        return exists(selectOne()
            .from(CODE_GRAPH_CLASS)
            .where(CODE_GRAPH_CLASS.GRAPH_NAME.eq(graphName))
            .and(CODE_GRAPH_CLASS.CLASS_NAME.eq(className))
            .and(CODE_GRAPH_CLASS.SOURCE_NAME.eq(sourceName)));
    }
}
