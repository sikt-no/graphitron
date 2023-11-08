package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Inventory;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class FilmDBQueries {
    public Map<String, List<Inventory>> inventoryForFilm(DSLContext ctx, Set<String> filmIds,
                                                         Integer pageSize, String after, SelectionSet select) {
        var film_inventoryfilmidfkey_inventory_left = INVENTORY.as("film_2576628581");
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(
                                film_inventoryfilmidfkey_inventory_left.getId().as("id"),
                                select.optional("storeId", film_inventoryfilmidfkey_inventory_left.STORE_ID).as("storeId")
                        ).mapping(Functions.nullOnAllNull(Inventory::new)).as("inventory")
                )
                .from(FILM)
                .leftJoin(film_inventoryfilmidfkey_inventory_left)
                .onKey(INVENTORY__INVENTORY_FILM_ID_FKEY)
                .where(FILM.hasIds(filmIds))
                .orderBy(INVENTORY.getIdFields())
                .seek(INVENTORY.getIdValues(after))
                .limit(pageSize + 1)
                .fetchGroups(Record2::value1, Record2::value2);
    }

    public Integer countInventoryForFilm(DSLContext ctx, Set<String> filmIds) {
        var film_inventoryfilmidfkey_inventory_left = INVENTORY.as("film_2576628581");
        return ctx
                .select(DSL.count().as("totalCount"))
                .from(FILM)
                .leftJoin(film_inventoryfilmidfkey_inventory_left)
                .onKey(INVENTORY__INVENTORY_FILM_ID_FKEY)
                .where(FILM.hasIds(filmIds))
                .fetchOne(0, Integer.class);
    }
}
