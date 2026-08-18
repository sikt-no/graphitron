package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.INTENT_BOUND_TABLE;

/**
 * Which catalog table an SDL type is bound to: one row of {@code intent_bound_table} per candidate,
 * keyed on the type name. This is the question the language server used to put to the classifier's
 * name-keyed projection, and the answer it gets here is the same derivation the column-match
 * classifier stands on rather than a second reading of {@code @table(name:)}.
 *
 * <p>Keyed on the declared type name, so an {@code extend type} site whose base declaration lives in
 * another file resolves like the base does. That was the reason the incumbent went through a
 * name-keyed projection instead of reading the directive off the node in hand, and it survives the
 * move: a type's binding is a property of the type, not of the declaration the cursor is inside.
 *
 * <p>An ambiguous binding arrives as more than one element, in schema order, never as a decline.
 * A surface offering candidates offers each of them, because each is a table the author might mean
 * and the qualifier that tells them apart is grammar the directive accepts; a surface that must pick
 * one has a resolution question on its hands, and the view's {@code candidates} column is what lets
 * a reader refuse to answer it (the column-match classifier requires exactly one).
 */
public final class BoundTables {

    private BoundTables() {}

    /**
     * The tables {@code typeName}'s {@code @table} binding resolves to, ordered by schema then
     * table name. Empty when the type carries no {@code @table}, when its reference resolves against
     * nothing in this graph's catalog sources, and on a root operation type, whose binding the
     * classifier never reads.
     */
    public static List<CatalogTable> of(StoreHandle store, String typeName) {
        var rows = store.dsl()
            .select(INTENT_BOUND_TABLE.TABLE_SOURCE_NAME, INTENT_BOUND_TABLE.TABLE_SCHEMA,
                INTENT_BOUND_TABLE.TABLE_NAME)
            .from(INTENT_BOUND_TABLE)
            .where(INTENT_BOUND_TABLE.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_BOUND_TABLE.TYPE_NAME.eq(typeName))
            .orderBy(INTENT_BOUND_TABLE.TABLE_SCHEMA, INTENT_BOUND_TABLE.TABLE_NAME)
            .fetch();
        var tables = new ArrayList<CatalogTable>(rows.size());
        for (var row : rows) {
            tables.add(new CatalogTable(row.value1(), row.value2(), row.value3()));
        }
        return tables;
    }

}
