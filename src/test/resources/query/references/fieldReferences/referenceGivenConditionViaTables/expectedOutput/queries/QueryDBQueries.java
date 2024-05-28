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
    public Rental rentalForQuery(DSLContext ctx, String id, SelectionSet select) {
        var rental_inventory_film_film_filmactor_film_actor = FILM_ACTOR.as("rental_4209375040");
        return ctx
                .select(
                        DSL.row(
                                RENTAL.getId().as("id"),
                                select.optional("mainActorLastName", rental_inventory_film_film_filmactor_film_actor.actor().LAST_NAME).as("mainActorLastName")
                        ).mapping(Functions.nullOnAllNull(Rental::new)).as("rental")
                )
                .from(RENTAL)
                .join(rental_inventory_film_film_filmactor_film_actor)
                .on(no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions.film_filmActor(RENTAL.inventory().film(), rental_inventory_film_film_filmactor_film_actor))
                .where(RENTAL.ID.eq(id))
                .fetchOne(0, Rental.class);
    }
}
