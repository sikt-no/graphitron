package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.FilmDataA;
import fake.graphql.example.model.FilmDataB;
import fake.graphql.example.model.FilmDataC;
import fake.graphql.example.model.Inventory;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static List<Film> filmsForQuery(DSLContext ctx, Integer pageSize, String after,
            SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId(),
                                select.optional("title", FILM.TITLE),
                                DSL.row(
                                        FILM.DESCRIPTION
                                ).mapping(Functions.nullOnAllNull(FilmDataA::new)),
                                DSL.row(
                                        FILM.LENGTH
                                ).mapping(Functions.nullOnAllNull(FilmDataB::new)),
                                DSL.row(
                                        FILM.RELEASE_YEAR
                                ).mapping(Functions.nullOnAllNull(FilmDataC::new))
                        ).mapping((a0, a1, a2_0, a2_1, a2_2) -> new FilmConnection(a0, a1, a2_0 != null ? a2_0 : a2_1 != null ? a2_1 : a2_2))
                )
                .from(FILM)
                .orderBy(FILM.getIdFields())
                .seek(FILM.getIdValues(after))
                .limit(pageSize + 1)
                .fetch(it -> it.into(Film.class));
    }
    public static List<Inventory> inventoryForQuery(DSLContext ctx, Integer pageSize, String after,
            SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                INVENTORY.getId()
                        ).mapping(Functions.nullOnAllNull(Inventory::new))
                )
                .from(INVENTORY)
                .orderBy(INVENTORY.getIdFields())
                .seek(INVENTORY.getIdValues(after))
                .limit(pageSize + 1)
                .fetch(it -> it.into(Inventory.class));
    }
    public static Integer countFilmsForQuery(DSLContext ctx) {
        return ctx
                .select(DSL.count())
                .from(FILM)
                .fetchOne(0, Integer.class);
    }
    public static Integer countInventoryForQuery(DSLContext ctx) {
        return ctx
                .select(DSL.count())
                .from(INVENTORY)
                .fetchOne(0, Integer.class);
    }
}
