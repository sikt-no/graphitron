package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.FilmActor;
import fake.graphql.example.model.Inventory;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record1;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public List<Inventory> inventoryForQuery(DSLContext ctx, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                INVENTORY.getId().as("id"),
                                DSL.multiset(
                                    DSL.select(
                                        DSL.row(
                                                FILM.getId(),
                                                FILM.TITLE,
                                                DSL.multiset(
                                                    DSL.select(
                                                        DSL.row(
                                                                FILM_ACTOR.getId()
                                                        ).mapping(Functions.nullOnAllNull(FilmActor::new))
                                                    )
                                                    .from(FILM_ACTOR)
                                                    .where(FILM_ACTOR.FILM_ID.eq(FILM.FILM_ID))
                                                )
                                        ).mapping((a0, a1, a2) -> new Film(a0, a1, a2.map(Record1::value1)))
                                    )
                                    .from(FILM)
                                    .where(INVENTORY.FILM_ID.eq(INVENTORY.film().FILM_ID))
                                )
                        ).mapping((a0, a1) -> new Inventory(a0, a1.map(Record1::value1))).as("inventory")
                )
                .from(INVENTORY)
                .orderBy(INVENTORY.getIdFields())
                .fetch(it -> it.into(Inventory.class));
    }
}
