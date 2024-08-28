package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Rental;
import java.lang.String;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static Rental rentalForQuery(DSLContext ctx, String id, SelectionSet select) {
        var rental_inventory_left = RENTAL.inventory().as("inventory_3858359801");
        var inventory_3858359801_film_left = rental_inventory_left.film().as("film_3414949773");
        var rental_film_filmactor_film_actor_left = FILM_ACTOR.as("rental_1817618132");
        var rental_1817618132_actor_left = rental_film_filmactor_film_actor_left.actor().as("actor_443501156");
        return ctx
                .select(
                        DSL.row(
                                RENTAL.getId(),
                                select.optional("mainActorLastName", rental_1817618132_actor_left.LAST_NAME)
                        ).mapping(Functions.nullOnAllNull(Rental::new))
                )
                .from(RENTAL)
                .leftJoin(rental_inventory_left)
                .leftJoin(inventory_3858359801_film_left)
                .leftJoin(rental_film_filmactor_film_actor_left)
                .on(no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions.film_filmActor(inventory_3858359801_film_left, rental_film_filmactor_film_actor_left))
                .leftJoin(rental_1817618132_actor_left)
                .where(RENTAL.hasId(id))
                .fetchOne(it -> it.into(Rental.class));
    }
}
