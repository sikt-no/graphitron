package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Inventory;
import fake.graphql.example.model.InventoryOrder;
import java.util.List;
import java.util.Map;
import no.fellesstudentsystem.graphql.helpers.query.QueryHelper;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static List<Inventory> inventoriesForQuery(DSLContext ctx, InventoryOrder order,
            SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                INVENTORY.getId().as("id"),
                                INVENTORY.STORE_ID.as("storeId"),
                                INVENTORY.FILM_ID.as("filmId")
                        ).mapping(Functions.nullOnAllNull(Inventory::new)).as("inventories")
                )
                .from(INVENTORY)
                .orderBy(
                    order == null
                            ? INVENTORY.getIdFields()
                            : QueryHelper.getSortFields(INVENTORY.getIndexes(), Map.ofEntries(
                                Map.entry("STORE_ID_FILM_ID", "idx_store_id_film_id"))
                            .get(order.getOrderByField().toString()), order.getDirection().toString()))
                .fetch(it -> it.into(Inventory.class));
    }
}
