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
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;
public class RentalDBQueries {
    public static Map<String, FilmActor> mainActorForRental(DSLContext ctx, Set<String> rentalIds,
            SelectionSet select) {
        var rental_inventory_left = RENTAL.inventory().as("inventory_3858359801");
        var inventory_3858359801_film_left = rental_inventory_left.film().as("film_3414949773");
        var rental_film_filmactor_film_actor_left = FILM_ACTOR.as("rental_1817618132");
        var rental_1817618132_actor_left = rental_film_filmactor_film_actor_left.actor().as("actor_443501156");
        return ctx
                .select(
                        RENTAL.getId(),
                        DSL.row(
                                rental_film_filmactor_film_actor_left.getId(),
                                DSL.row(
                                        rental_1817618132_actor_left.getId(),
                                        select.optional("actor/lastName", rental_1817618132_actor_left.LAST_NAME)
                                ).mapping(Functions.nullOnAllNull(Actor::new))
                        ).mapping(Functions.nullOnAllNull(FilmActor::new))
                )
                .from(RENTAL)
                .leftJoin(rental_inventory_left)
                .leftJoin(inventory_3858359801_film_left)
                .leftJoin(rental_film_filmactor_film_actor_left)
                .on(no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions.film_filmActor(inventory_3858359801_film_left, rental_film_filmactor_film_actor_left))
                .leftJoin(rental_1817618132_actor_left)
                .where(RENTAL.hasIds(rentalIds))
                .orderBy(rental_film_filmactor_film_actor_left.getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
}
