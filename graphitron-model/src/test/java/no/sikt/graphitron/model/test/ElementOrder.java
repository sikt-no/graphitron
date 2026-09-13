package no.sikt.graphitron.model.test;

import org.jooq.Field;
import org.jooq.Table;

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_VALUE_ENTRY;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.select;

/**
 * The index an author wrote a decoded element at, for a case that wants to assert on the order.
 *
 * <p>A decode of a list element is keyed by the element it decodes and states nothing about where in
 * the list that element sits, which is a fact the element itself already carries. So a case asking
 * for the index asks the value entry, and this is that question spelled once rather than as a join
 * repeated across every such case.
 */
public final class ElementOrder {

    private ElementOrder() {}

    /**
     * The position of the element {@code decode} decodes, as a scalar over {@code decode}'s own key.
     * Correlated rather than joined so a case reads it as a column of the relation it is already
     * selecting from, which is how the relation used to offer it.
     *
     * <p>Zero where the element has no index of its own, which is the value an author wrote without
     * a list around it: GraphQL coerces a lone value to the list of one, and a value at the root of
     * an expression sits in no enclosing list to take a position from.
     */
    public static Field<Integer> writtenAt(Table<?> decode) {
        var v = GRAPHQL_AST_VALUE_ENTRY;
        return field(select(coalesce(v.POSITION, inline(0))).from(v)
            .where(v.GRAPH_NAME.eq(decode.field(v.GRAPH_NAME)))
            .and(v.SOURCE_NAME.eq(decode.field(v.SOURCE_NAME)))
            .and(v.SOURCE_LINE.eq(decode.field(v.SOURCE_LINE)))
            .and(v.SOURCE_COLUMN.eq(decode.field(v.SOURCE_COLUMN))));
    }
}
