package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Description;
import fake.graphql.example.package.model.Film;
import fake.graphql.example.package.model.Store;
import fake.graphql.example.package.model.StoreFieldsAllRequired;
import fake.graphql.example.package.model.StoreFieldsWithOptional;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class InventoryDBQueries {
    public Map<String, Store> storeForInventory(DSLContext ctx, Set<String> inventoryIds,
                                                SelectionSet select) {
        return ctx
                .select(
                        INVENTORY.getId(),
                        DSL.row(
                                INVENTORY.store().getId().as("id"),
                                DSL.row(
                                        select.optional("fields/filmId", INVENTORY.store().FILM_ID).as("filmId"),
                                        select.optional("fields/lastUpdate", INVENTORY.store().LAST_UPDATE).as("lastUpdate")
                                ).mapping(StoreFieldsWithOptional::new).as("fields"),
                                DSL.row(
                                        select.optional("navnOpt/filmId", INVENTORY.store().FILM_ID).as("filmId"),
                                        select.optional("navnOpt/lastUpdate", INVENTORY.store().LAST_UPDATE).as("lastUpdate")
                                ).mapping(Functions.nullOnAllNull(StoreFieldsWithOptional::new)).as("navnOpt"),
                                DSL.row(
                                        select.optional("navnReq/filmId", INVENTORY.store().FILM_ID).as("filmId"),
                                        select.optional("navnReq/addressId", INVENTORY.store().ADDRESS_ID).as("addressId")
                                ).mapping(Functions.nullOnAllNull(StoreFieldsAllRequired::new)).as("navnReq")
                        ).mapping((a0, a1, a2, a3) -> a0 == null && (a1 == null || new StoreFieldsWithOptional().equals(a1)) && (a2 == null || new StoreFieldsWithOptional().equals(a2)) && (a3 == null || new StoreFieldsAllRequired().equals(a3)) ? null : new Store(a0, a1, a2, a3)).as("store")
                )
                .from(INVENTORY)
                .where(INVENTORY.hasIds(inventoryIds))
                .fetchMap(Record2::value1, Record2::value2);
    }

    public Map<String, Film> filmForInventory(DSLContext ctx, Set<String> inventoryIds,
                                              SelectionSet select) {
        return ctx
                .select(
                        INVENTORY.getId(),
                        DSL.row(
                                INVENTORY.film().getId().as("id"),
                                select.optional("title", INVENTORY.film().TITLE).as("title"),
                                select.optional("title2", INVENTORY.film().TITLE).as("title2"),
                                select.optional("title3", INVENTORY.film().TITLE).as("title3"),
                                select.optional("title4", INVENTORY.film().TITLE).as("title4"),
                                select.optional("title5", INVENTORY.film().TITLE).as("title5"),
                                select.optional("title6", INVENTORY.film().TITLE).as("title6"),
                                select.optional("title7", INVENTORY.film().TITLE).as("title7"),
                                select.optional("title8", INVENTORY.film().TITLE).as("title8"),
                                select.optional("title9", INVENTORY.film().TITLE).as("title9"),
                                select.optional("title10", INVENTORY.film().TITLE).as("title10"),
                                select.optional("title11", INVENTORY.film().TITLE).as("title11"),
                                select.optional("title12", INVENTORY.film().TITLE).as("title12"),
                                select.optional("title13", INVENTORY.film().TITLE).as("title13"),
                                select.optional("title14", INVENTORY.film().TITLE).as("title14"),
                                select.optional("title15", INVENTORY.film().TITLE).as("title15"),
                                select.optional("title16", INVENTORY.film().TITLE).as("title16"),
                                select.optional("title17", INVENTORY.film().TITLE).as("title17"),
                                select.optional("title18", INVENTORY.film().TITLE).as("title18"),
                                select.optional("title19", INVENTORY.film().TITLE).as("title19"),
                                select.optional("title20", INVENTORY.film().TITLE).as("title20"),
                                select.optional("title21", INVENTORY.film().TITLE).as("title21"),
                                select.optional("title22", INVENTORY.film().TITLE).as("title22"),
                                select.optional("title23", INVENTORY.film().TITLE).as("title23"),
                                DSL.row(
                                        select.optional("description/description", INVENTORY.film().DESCRIPTION).as("description"),
                                        select.optional("description/description2", INVENTORY.film().DESCRIPTION).as("description2"),
                                        select.optional("description/description3", INVENTORY.film().DESCRIPTION).as("description3"),
                                        select.optional("description/description4", INVENTORY.film().DESCRIPTION).as("description4"),
                                        select.optional("description/description5", INVENTORY.film().DESCRIPTION).as("description5"),
                                        select.optional("description/description6", INVENTORY.film().DESCRIPTION).as("description6"),
                                        select.optional("description/description7", INVENTORY.film().DESCRIPTION).as("description7"),
                                        select.optional("description/description8", INVENTORY.film().DESCRIPTION).as("description8"),
                                        select.optional("description/description9", INVENTORY.film().DESCRIPTION).as("description9"),
                                        select.optional("description/description10", INVENTORY.film().DESCRIPTION).as("description10"),
                                        select.optional("description/description11", INVENTORY.film().DESCRIPTION).as("description11"),
                                        select.optional("description/description12", INVENTORY.film().DESCRIPTION).as("description12"),
                                        select.optional("description/description13", INVENTORY.film().DESCRIPTION).as("description13"),
                                        select.optional("description/description14", INVENTORY.film().DESCRIPTION).as("description14"),
                                        select.optional("description/description15", INVENTORY.film().DESCRIPTION).as("description15"),
                                        select.optional("description/description16", INVENTORY.film().DESCRIPTION).as("description16"),
                                        select.optional("description/description17", INVENTORY.film().DESCRIPTION).as("description17"),
                                        select.optional("description/description18", INVENTORY.film().DESCRIPTION).as("description18"),
                                        select.optional("description/description19", INVENTORY.film().DESCRIPTION).as("description19"),
                                        select.optional("description/description20", INVENTORY.film().DESCRIPTION).as("description20"),
                                        select.optional("description/description21", INVENTORY.film().DESCRIPTION).as("description21"),
                                        select.optional("description/description22", INVENTORY.film().DESCRIPTION).as("description22"),
                                        select.optional("description/description23", INVENTORY.film().DESCRIPTION).as("description23")
                                ).mapping(Description.class, r ->
                                        new Description(
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[0]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[1]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[2]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[3]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[4]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[5]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[6]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[7]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[8]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[9]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[10]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[11]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[12]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[13]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[14]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[15]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[16]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[17]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[18]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[19]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[20]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[21]),
                                                INVENTORY.film().DESCRIPTION.getDataType().convert(r[22])
                                        )
                                ).as("description")
                        ).mapping(Film.class, r ->
                                r[0] == null && r[1] == null && r[2] == null && r[3] == null && r[4] == null && r[5] == null && r[6] == null && r[7] == null && r[8] == null && r[9] == null && r[10] == null && r[11] == null && r[12] == null && r[13] == null && r[14] == null && r[15] == null && r[16] == null && r[17] == null && r[18] == null && r[19] == null && r[20] == null && r[21] == null && r[22] == null && r[23] == null && (r[24] == null || new Description().equals(r[24])) ? null : new Film(
                                        (String) r[0],
                                        INVENTORY.film().TITLE.getDataType().convert(r[1]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[2]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[3]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[4]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[5]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[6]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[7]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[8]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[9]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[10]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[11]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[12]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[13]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[14]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[15]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[16]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[17]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[18]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[19]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[20]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[21]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[22]),
                                        INVENTORY.film().TITLE.getDataType().convert(r[23]),
                                        (Description) r[24]
                                )
                        ).as("film")
                )
                .from(INVENTORY)
                .where(INVENTORY.hasIds(inventoryIds))
                .fetchMap(Record2::value1, Record2::value2);
    }
}
