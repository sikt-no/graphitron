package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Film;
import fake.graphql.example.package.model.FilmDataA;
import fake.graphql.example.package.model.FilmDataB;
import fake.graphql.example.package.model.FilmDataC;
import fake.graphql.example.package.model.Inventory;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public List<Film> filmsForQuery(DSLContext ctx, Integer pageSize, String after,
                                    SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("title", FILM.TITLE).as("title"),
                                DSL.row(
                                        FILM.DESCRIPTION.as("description")
                                ).mapping(Functions.nullOnAllNull(FilmDataA::new)).as("filmDetailsA"),
                                DSL.row(
                                        FILM.LENGTH.as("length")
                                ).mapping(Functions.nullOnAllNull(FilmDataB::new)).as("filmDetailsA"),
                                DSL.row(
                                        FILM.RELEASE_YEAR.as("releaseYear")
                                ).mapping(Functions.nullOnAllNull(FilmDataC::new)).as("filmDetailsA")
                        ).mapping((a0, a1, a2_0, a2_1, a2_2) -> new FilmConnection(a0, a1, a2_0 != null ? a2_0 : a2_1 != null ? a2_1 : a2_2)).as("films")
                )
                .from(FILM)
                .orderBy(FILM.getIdFields())
                .seek(FILM.getIdValues(after))
                .limit(pageSize + 1)
                .fetch(0, Film.class);
    }

    public List<Inventory> inventoryForQuery(DSLContext ctx, Integer pageSize, String after,
                                             SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                INVENTORY.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Inventory::new)).as("inventory")
                )
                .from(INVENTORY)
                .orderBy(INVENTORY.getIdFields())
                .seek(INVENTORY.getIdValues(after))
                .limit(pageSize + 1)
                .fetch(0, Inventory.class);
    }

    public Integer countFilmsForQuery(DSLContext ctx) {
        return ctx
                .select(DSL.count().as("totalCount"))
                .from(FILM)
                .fetchOne(0, Integer.class);
    }

    public Integer countInventoryForQuery(DSLContext ctx) {
        return ctx
                .select(DSL.count().as("totalCount"))
                .from(INVENTORY)
                .fetchOne(0, Integer.class);
    }
}