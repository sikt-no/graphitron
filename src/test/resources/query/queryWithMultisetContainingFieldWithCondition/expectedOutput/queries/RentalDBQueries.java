package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Film;
import fake.graphql.example.model.FilmActor;
import fake.graphql.example.model.Inventory;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class RentalDBQueries {
    public static Map<String, Inventory> inventoryForRental(DSLContext ctx, Set<String> rentalIds,
                                                     SelectionSet select) {
        var film_filmactorfilmidfkey_film_actor = FILM_ACTOR.as("film_518717159");
        return ctx
                .select(
                        RENTAL.getId(),
                        DSL.row(
                                RENTAL.inventory().getId(),
                                DSL.multiset(
                                        DSL.select(
                                                        DSL.row(
                                                                FILM.getId(),
                                                                FILM.TITLE,
                                                                DSL.row(
                                                                        film_filmactorfilmidfkey_film_actor.getId()
                                                                ).mapping(Functions.nullOnAllNull(FilmActor::new))
                                                        ).mapping(Functions.nullOnAllNull(Film::new))
                                                )
                                                .from(FILM)
                                                .join(film_filmactorfilmidfkey_film_actor)
                                                .onKey(FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY)
                                                .where(INVENTORY.FILM_ID.eq(RENTAL.inventory().film().FILM_ID))
                                                .and(no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions.film_filmActor(FILM, film_filmactorfilmidfkey_film_actor))
                                )
                        ).mapping((a0, a1) -> new Inventory(a0, a1.map(Record1::value1)))
                )
                .from(RENTAL)
                .where(RENTAL.hasIds(rentalIds))
                .orderBy(RENTAL.inventory().getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
}