package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Inventory;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public List<Inventory> inventoryForQuery(DSLContext ctx, String id, String lastName,
                                             SelectionSet select) {
        var film_film_actor_mainactor = FILM_ACTOR.as("FILM_809568702");
        return ctx
                .select(
                        DSL.row(
                                INVENTORY.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Inventory::new)).as("inventory")
                )
                .from(INVENTORY)
                .leftJoin(film_film_actor_mainactor)
                .on(no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions.mainActor(INVENTORY.film(), film_film_actor_mainactor))
                .where(INVENTORY.ID.eq(id))
                .and(film_film_actor_mainactor.LAST_NAME.eq(lastName))
                .orderBy(INVENTORY.getIdFields())
                .fetch(0, Inventory.class);
    }
}