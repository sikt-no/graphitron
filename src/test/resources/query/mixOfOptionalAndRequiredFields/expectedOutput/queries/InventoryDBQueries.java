package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Description;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.Store;
import fake.graphql.example.model.StoreFieldsAllRequired;
import fake.graphql.example.model.StoreFieldsWithOptional;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;
public class InventoryDBQueries {
    public static Map<String, Store> storeForInventory(DSLContext ctx, Set<String> inventoryIds,
            SelectionSet select) {
        var inventory_store_left = INVENTORY.store().as("store_3677363237");
        return ctx
                .select(
                        INVENTORY.getId(),
                        DSL.row(
                                inventory_store_left.getId(),
                                DSL.row(
                                        select.optional("fields/filmId", inventory_store_left.FILM_ID),
                                        select.optional("fields/lastUpdate", inventory_store_left.LAST_UPDATE)
                                ).mapping(StoreFieldsWithOptional::new),
                                DSL.row(
                                        select.optional("navnOpt/filmId", inventory_store_left.FILM_ID),
                                        select.optional("navnOpt/lastUpdate", inventory_store_left.LAST_UPDATE)
                                ).mapping(Functions.nullOnAllNull(StoreFieldsWithOptional::new)),
                                DSL.row(
                                        select.optional("navnReq/filmId", inventory_store_left.FILM_ID),
                                        select.optional("navnReq/addressId", inventory_store_left.ADDRESS_ID)
                                ).mapping(Functions.nullOnAllNull(StoreFieldsAllRequired::new))
                        ).mapping((a0, a1, a2, a3) -> a0 == null && (a1 == null || new StoreFieldsWithOptional().equals(a1)) && (a2 == null || new StoreFieldsWithOptional().equals(a2)) && (a3 == null || new StoreFieldsAllRequired().equals(a3)) ? null : new Store(a0, a1, a2, a3))
                )
                .from(INVENTORY)
                .leftJoin(inventory_store_left)
                .where(INVENTORY.hasIds(inventoryIds))
                .orderBy(inventory_store_left.getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
    public static Map<String, Film> filmForInventory(DSLContext ctx, Set<String> inventoryIds,
            SelectionSet select) {
        var inventory_film_left = INVENTORY.film().as("film_2557797379");
        return ctx
                .select(
                        INVENTORY.getId(),
                        DSL.row(
                                inventory_film_left.getId(),
                                select.optional("title", inventory_film_left.TITLE),
                                select.optional("title2", inventory_film_left.TITLE),
                                select.optional("title3", inventory_film_left.TITLE),
                                select.optional("title4", inventory_film_left.TITLE),
                                select.optional("title5", inventory_film_left.TITLE),
                                select.optional("title6", inventory_film_left.TITLE),
                                select.optional("title7", inventory_film_left.TITLE),
                                select.optional("title8", inventory_film_left.TITLE),
                                select.optional("title9", inventory_film_left.TITLE),
                                select.optional("title10", inventory_film_left.TITLE),
                                select.optional("title11", inventory_film_left.TITLE),
                                select.optional("title12", inventory_film_left.TITLE),
                                select.optional("title13", inventory_film_left.TITLE),
                                select.optional("title14", inventory_film_left.TITLE),
                                select.optional("title15", inventory_film_left.TITLE),
                                select.optional("title16", inventory_film_left.TITLE),
                                select.optional("title17", inventory_film_left.TITLE),
                                select.optional("title18", inventory_film_left.TITLE),
                                select.optional("title19", inventory_film_left.TITLE),
                                select.optional("title20", inventory_film_left.TITLE),
                                select.optional("title21", inventory_film_left.TITLE),
                                select.optional("title22", inventory_film_left.TITLE),
                                select.optional("title23", inventory_film_left.TITLE),
                                DSL.row(
                                        select.optional("description/description", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description2", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description3", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description4", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description5", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description6", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description7", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description8", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description9", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description10", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description11", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description12", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description13", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description14", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description15", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description16", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description17", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description18", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description19", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description20", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description21", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description22", inventory_film_left.DESCRIPTION),
                                        select.optional("description/description23", inventory_film_left.DESCRIPTION)
                                ).mapping(Description.class, r ->
                                        new Description(
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[0]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[1]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[2]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[3]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[4]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[5]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[6]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[7]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[8]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[9]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[10]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[11]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[12]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[13]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[14]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[15]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[16]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[17]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[18]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[19]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[20]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[21]),
                                                inventory_film_left.DESCRIPTION.getDataType().convert(r[22])
                                        )
                                )
                        ).mapping(Film.class, r ->
                                r[0] == null && r[1] == null && r[2] == null && r[3] == null && r[4] == null && r[5] == null && r[6] == null && r[7] == null && r[8] == null && r[9] == null && r[10] == null && r[11] == null && r[12] == null && r[13] == null && r[14] == null && r[15] == null && r[16] == null && r[17] == null && r[18] == null && r[19] == null && r[20] == null && r[21] == null && r[22] == null && r[23] == null && (r[24] == null || new Description().equals(r[24])) ? null : new Film(
                                        (String) r[0],
                                        inventory_film_left.TITLE.getDataType().convert(r[1]),
                                        inventory_film_left.TITLE.getDataType().convert(r[2]),
                                        inventory_film_left.TITLE.getDataType().convert(r[3]),
                                        inventory_film_left.TITLE.getDataType().convert(r[4]),
                                        inventory_film_left.TITLE.getDataType().convert(r[5]),
                                        inventory_film_left.TITLE.getDataType().convert(r[6]),
                                        inventory_film_left.TITLE.getDataType().convert(r[7]),
                                        inventory_film_left.TITLE.getDataType().convert(r[8]),
                                        inventory_film_left.TITLE.getDataType().convert(r[9]),
                                        inventory_film_left.TITLE.getDataType().convert(r[10]),
                                        inventory_film_left.TITLE.getDataType().convert(r[11]),
                                        inventory_film_left.TITLE.getDataType().convert(r[12]),
                                        inventory_film_left.TITLE.getDataType().convert(r[13]),
                                        inventory_film_left.TITLE.getDataType().convert(r[14]),
                                        inventory_film_left.TITLE.getDataType().convert(r[15]),
                                        inventory_film_left.TITLE.getDataType().convert(r[16]),
                                        inventory_film_left.TITLE.getDataType().convert(r[17]),
                                        inventory_film_left.TITLE.getDataType().convert(r[18]),
                                        inventory_film_left.TITLE.getDataType().convert(r[19]),
                                        inventory_film_left.TITLE.getDataType().convert(r[20]),
                                        inventory_film_left.TITLE.getDataType().convert(r[21]),
                                        inventory_film_left.TITLE.getDataType().convert(r[22]),
                                        inventory_film_left.TITLE.getDataType().convert(r[23]),
                                        (Description) r[24]
                                )
                        )
                )
                .from(INVENTORY)
                .leftJoin(inventory_film_left)
                .where(INVENTORY.hasIds(inventoryIds))
                .orderBy(inventory_film_left.getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
}
