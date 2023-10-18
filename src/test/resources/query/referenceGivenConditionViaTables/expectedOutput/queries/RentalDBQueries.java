package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Actor;
import fake.graphql.example.package.model.FilmActor;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class RentalDBQueries {
    public Map<String, FilmActor> mainActorForRental(DSLContext ctx, Set<String> rentalIder,
            SelectionSet select) {
        var film_mainactor = FILM_ACTOR.as("FILM_941767556");
        var rental_inventory = RENTAL.inventory().as("RENTAL_4126102745");
        var rental_inventory_film = rental_inventory.film().as("INVENTORY_3033617302");
        return ctx
                .select(
                        RENTAL.getId(),
                        DSL.row(
                                film_mainactor.getId().as("id"),
                                DSL.row(
                                        film_mainactor.actor().getId().as("id"),
                                        select.optional("actor/lastName", film_mainactor.actor().LAST_NAME).as("lastName")
                                ).mapping(Functions.nullOnAllNull(Actor::new)).as("actor")
                        ).mapping(Functions.nullOnAllNull(FilmActor::new)).as("mainActor")
                )
                .from(RENTAL)
                .leftJoin(film_mainactor)
                .on(no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions.mainActor(rental_inventory_film, film_mainactor))
                .where(RENTAL.hasIds(rentalIder))
                .fetchMap(Record2::value1, Record2::value2);
    }
}