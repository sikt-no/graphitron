package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.Inventory;
import fake.graphql.example.model.Rental;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;
public class InventoryDBQueries {
    public static Map<String, List<Rental>> rentalsForInventory(DSLContext ctx,
            Set<String> inventoryIds, SelectionSet select) {
        var inventory_rental_left = INVENTORY.rental().as("rental_2178516071");
        return ctx
                .select(
                        INVENTORY.getId(),
                        DSL.row(inventory_rental_left.getId()).mapping(Functions.nullOnAllNull(Rental::new))
                )
                .from(INVENTORY)
                .leftJoin(inventory_rental_left)
                .where(INVENTORY.hasIds(inventoryIds))
                .orderBy(inventory_rental_left.getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
    public static Map<String, Inventory> loadInventoryByIdsAsNode(DSLContext ctx, Set<String> ids,
            SelectionSet select) {
        var inventory_film_left = INVENTORY.film().as("film_2557797379");
        return ctx
                .select(
                        INVENTORY.getId(),
                        DSL.row(
                                INVENTORY.getId(),
                                DSL.row(
                                        inventory_film_left.getId(),
                                        select.optional("film/title", inventory_film_left.TITLE)
                                ).mapping(Functions.nullOnAllNull(Film::new))
                        ).mapping(Functions.nullOnAllNull(Inventory::new))
                )
                .from(INVENTORY)
                .leftJoin(inventory_film_left)
                .where(INVENTORY.hasIds(ids))
                .fetchMap(Record2::value1, Record2::value2);
    }
}
