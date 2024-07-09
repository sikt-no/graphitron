package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Actor;
import fake.graphql.example.model.FilmActor;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class RentalDBQueries {
    public static Map<String, FilmActor> mainActorForRental(DSLContext ctx, Set<String> rentalIds,
                                                     SelectionSet select) {
        var rental_inventory_film_film_filmactor_film_actor = FILM_ACTOR.as("rental_4209375040");
        return ctx
                .select(
                        RENTAL.getId(),
                        DSL.row(
                                rental_inventory_film_film_filmactor_film_actor.getId(),
                                DSL.row(
                                        rental_inventory_film_film_filmactor_film_actor.actor().getId(),
                                        select.optional("actor/lastName", rental_inventory_film_film_filmactor_film_actor.actor().LAST_NAME)
                                ).mapping(Functions.nullOnAllNull(Actor::new))
                        ).mapping(Functions.nullOnAllNull(FilmActor::new))
                )
                .from(RENTAL)
                .join(rental_inventory_film_film_filmactor_film_actor)
                .on(no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions.film_filmActor(RENTAL.inventory().film(), rental_inventory_film_film_filmactor_film_actor))
                .where(RENTAL.hasIds(rentalIds))
                .orderBy(rental_inventory_film_film_filmactor_film_actor.getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
}
