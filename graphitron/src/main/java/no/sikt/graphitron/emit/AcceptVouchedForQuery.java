package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.List;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ACCEPT_VOUCHED_FOR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.min;
import static org.jooq.impl.DSL.select;

/**
 * The shape of what a graph's {@code @accept} fields vouch for: each vouched-for field by name, with the
 * Java type of the column it reads at its type's table. Every {@code @accept} in a graph has the one
 * shape, the store refusing one whose shape differs from the first accepting type's
 * ({@code VOUCHER_ACCEPT_VOUCHED_FOR_DIFFERS}), so the shape is read off that type. Empty in a graph that
 * accepts nothing.
 *
 * <p>Rows are in store terms: the field's name and the column's binding type as the catalog states it;
 * {@link ConnectionKeyShape#read} maps them to the generated {@code ConnectionKey}'s components.
 */
public final class AcceptVouchedForQuery {

    private AcceptVouchedForQuery() {}

    /** One vouched-for field: its name and the fully qualified Java type of the column it reads. */
    public record Field(String fieldName, String bindingType) {
        public Field {
            Objects.requireNonNull(fieldName, "fieldName");
            Objects.requireNonNull(bindingType, "bindingType");
        }
    }

    /** The graph's vouched-for fields in name order, or none where the graph accepts nothing. */
    public static List<Field> read(StoreHandle store) {
        var c = GRAPHITRON_ACCEPT_VOUCHED_FOR;
        var f = GRAPHITRON_FIELD;
        var tt = GRAPHITRON_TABLETYPE;
        var oc = GRAPHITRON_OCCURRENCE_COLUMN;
        var col = SQL_COLUMN;
        var first = select(min(c.TYPE_NAME)).from(c).where(c.GRAPH_NAME.eq(store.graphName()));
        return store.dsl()
            .selectDistinct(c.FIELD_NAME, col.BINDING_TYPE)
            .from(c)
            .join(f).on(f.GRAPH_NAME.eq(c.GRAPH_NAME), f.TYPE_NAME.eq(c.TYPE_NAME), f.FIELD_NAME.eq(c.FIELD_NAME))
            .join(tt).on(tt.GRAPH_NAME.eq(c.GRAPH_NAME), tt.TYPE_NAME.eq(c.TYPE_NAME))
            .join(oc).on(oc.GRAPH_NAME.eq(f.GRAPH_NAME), oc.COORDINATE.eq(f.COORDINATE),
                oc.BOUND.eq(tt.TABLE_SCHEMA.concat(inline(".")).concat(tt.TABLE_NAME)))
            .join(col).on(col.SOURCE_NAME.eq(oc.TABLE_SOURCE_NAME), col.TABLE_SCHEMA.eq(oc.TABLE_SCHEMA),
                col.TABLE_NAME.eq(oc.TABLE_NAME), col.COLUMN_NAME.eq(oc.COLUMN_NAME))
            .where(c.GRAPH_NAME.eq(store.graphName()), c.TYPE_NAME.eq(first))
            .orderBy(c.FIELD_NAME)
            .fetch(r -> new Field(r.value1(), r.value2()));
    }
}
