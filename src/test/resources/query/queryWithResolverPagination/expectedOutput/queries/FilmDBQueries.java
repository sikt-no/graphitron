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
    public Map<String, List<Inventory>> inventoryForFilm(DSLContext ctx, Set<String> filmIder,
            Integer pageSize, String after, SelectionSet select) {
        return ctx
                .select(
                        INVENTORY.getFilmId(),
                        DSL.row(
                                INVENTORY.getId().as("id"),
                                select.optional("storeId", INVENTORY.STORE_ID).as("storeId")
                        ).mapping(Functions.nullOnAllNull(Inventory::new)).as("inventory")
                )
                .from(INVENTORY)
                .where(INVENTORY.hasFilmIds(filmIder))
                .orderBy(INVENTORY.getIdFields())
                .seek(INVENTORY.getIdValues(after))
                .limit(pageSize + 1)
                .fetchGroups(Record2::value1, Record2::value2);
    }

    public Integer countInventoryForFilm(DSLContext ctx, Set<String> filmIder) {
        return ctx
                .select(DSL.count().as("totalCount"))
                .from(INVENTORY)
                .where(INVENTORY.hasFilmIds(filmIder))
                .fetchOne(0, Integer.class);
    }
}