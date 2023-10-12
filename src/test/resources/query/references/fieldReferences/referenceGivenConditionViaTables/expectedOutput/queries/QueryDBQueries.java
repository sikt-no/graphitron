package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Rental;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public List<Rental> rentalForQuery(DSLContext ctx, String id, SelectionSet select) {
        var film_film_actor_mainactor = FILM_ACTOR.as("FILM_809568702");
        return ctx
                .select(
                        DSL.row(
                                RENTAL.getId().as("id"),
                                select.optional("mainActorLastName", film_film_actor_mainactor.actor().LAST_NAME).as("mainActorLastName")
                        ).mapping(Functions.nullOnAllNull(Rental::new)).as("rental")
                )
                .from(RENTAL)
                .leftJoin(film_film_actor_mainactor)
                .on(no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions.mainActor(RENTAL.inventory().film(), film_film_actor_mainactor))
                .where(RENTAL.ID.eq(id))
                .orderBy(RENTAL.getIdFields())
                .fetch(0, Rental.class);
    }
}