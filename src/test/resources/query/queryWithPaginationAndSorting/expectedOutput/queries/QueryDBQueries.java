package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Film;
import fake.graphql.example.package.model.FilmOrder;
import fake.graphql.example.package.model.Inventory;
import fake.graphql.example.package.model.InventoryOrder;
import fake.graphql.example.package.model.Nested;
import java.lang.Integer;
import java.lang.Object;
import java.lang.String;
import java.util.List;
import java.util.Map;
import no.fellesstudentsystem.graphql.helpers.query.QueryHelper;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public List<Film> filmsForQuery(DSLContext ctx, String releaseYear, FilmOrder orderBy,
                                    Integer pageSize, String after, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                FILM.TITLE.as("title"),
                                FILM.DESCRIPTION.as("description"),
                                DSL.row(
                                        FILM.LANGUAGE_ID.as("languageId")
                                ).mapping(Functions.nullOnAllNull(Nested::new)).as("nested")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("films")
                )
                .from(FILM)
                .where(FILM.RELEASE_YEAR.eq(releaseYear))
                .orderBy(
                        orderBy == null
                                ? FILM.getIdFields()
                                : QueryHelper.getSortFields(FILM.getIndexes(), Map.ofEntries(
                                        Map.entry("LANGUAGE", "IDX_FK_LANGUAGE_ID"),
                                        Map.entry("TITLE", "IDX_TITLE"))
                                .get(orderBy.getOrderByField().toString()), orderBy.getDirection().toString()))
                .seek(
                        orderBy == null
                                ? FILM.getIdValues(after)
                                : after == null ? new Object[]{} : after.split(","))
                .limit(pageSize + 1)
                .fetch(0, Film.class);
    }

    public List<Inventory> inventoriesForQuery(DSLContext ctx, InventoryOrder orderBy,
                                               Integer pageSize, String after, SelectionSet select) {
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
                        orderBy == null
                                ? INVENTORY.getIdFields()
                                : QueryHelper.getSortFields(INVENTORY.getIndexes(), Map.ofEntries(
                                        Map.entry("STORE_ID_FILM_ID", "IDX_STORE_ID_FILM_ID"))
                                .get(orderBy.getOrderByField().toString()), orderBy.getDirection().toString()))
                .seek(
                        orderBy == null
                                ? INVENTORY.getIdValues(after)
                                : after == null ? new Object[]{} : after.split(","))
                .limit(pageSize + 1)
                .fetch(0, Inventory.class);
    }

    public Integer countFilmsForQuery(DSLContext ctx, String releaseYear) {
        return ctx
                .select(DSL.count().as("totalCount"))
                .from(FILM)
                .where(FILM.RELEASE_YEAR.eq(releaseYear))
                .fetchOne(0, Integer.class);
    }

    public Integer countInventoriesForQuery(DSLContext ctx) {
        return ctx
                .select(DSL.count().as("totalCount"))
                .from(INVENTORY)
                .fetchOne(0, Integer.class);
    }
}