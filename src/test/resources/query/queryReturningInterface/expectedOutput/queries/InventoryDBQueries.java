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
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class InventoryDBQueries {
    public Map<String, List<Rental>> rentalsForInventory(DSLContext ctx, Set<String> inventoryIds,
                                                         SelectionSet select) {
        var inventory_rentalinventoryidfkey_rental = RENTAL.as("inventory_1049504228");
        return ctx
                .select(
                        INVENTORY.getId(),
                        DSL.row(
                                inventory_rentalinventoryidfkey_rental.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Rental::new)).as("rentals")
                )
                .from(INVENTORY)
                .join(inventory_rentalinventoryidfkey_rental)
                .onKey(RENTAL__RENTAL_INVENTORY_ID_FKEY)
                .where(INVENTORY.hasIds(inventoryIds))
                .fetchGroups(Record2::value1, Record2::value2);
    }

    public Map<String, Inventory> loadInventoryByIdsAsNode(DSLContext ctx, Set<String> ids,
                                                           SelectionSet select) {
        return ctx
                .select(
                        INVENTORY.getId(),
                        DSL.row(
                                INVENTORY.getId().as("id"),
                                DSL.row(
                                        INVENTORY.film().getId().as("id"),
                                        select.optional("film/title", INVENTORY.film().TITLE).as("title")
                                ).mapping(Functions.nullOnAllNull(Film::new)).as("film")
                        ).mapping(Functions.nullOnAllNull(Inventory::new)).as("id")
                )
                .from(INVENTORY)
                .where(INVENTORY.hasIds(ids))
                .fetchMap(Record2::value1, Record2::value2);
    }
}
