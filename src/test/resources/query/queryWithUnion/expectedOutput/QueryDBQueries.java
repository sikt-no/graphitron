package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Film;
import fake.graphql.example.model.FilmDataA;
import fake.graphql.example.model.FilmDataB;
import fake.graphql.example.model.FilmDataC;
import fake.graphql.example.model.Inventory;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Film filmsForQuery(DSLContext ctx, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId(),
                                select.optional("title", FILM.TITLE),
                                DSL.row(FILM.DESCRIPTION).mapping(Functions.nullOnAllNull(FilmDataA::new)),
                                DSL.row(FILM.LENGTH).mapping(Functions.nullOnAllNull(FilmDataB::new)),
                                DSL.row(FILM.RELEASE_YEAR).mapping(Functions.nullOnAllNull(FilmDataC::new))
                        ).mapping((a0, a1, a2_0, a2_1, a2_2) -> new Film(a0, a1, a2_0 != null ? a2_0 : a2_1 != null ? a2_1 : a2_2))
                )
                .from(FILM)
                .fetchOne(it -> it.into(Film.class));
    }

    public static Inventory inventoryForQuery(DSLContext ctx, SelectionSet select) {
        return ctx
                .select(DSL.row(INVENTORY.getId()).mapping(Functions.nullOnAllNull(Inventory::new)))
                .from(INVENTORY)
                .fetchOne(it -> it.into(Inventory.class));
    }
}
