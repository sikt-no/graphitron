package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Actor;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class InventoryDBQueries {
    public Map<String, List<Actor>> mainActorsForInventory(DSLContext ctx,
                                                           Set<String> inventoryIds, SelectionSet select) {
        var film_film_actor_mainactor = FILM_ACTOR.as("FILM_809568702");
        return ctx
                .select(
                        INVENTORY.getId(),
                        DSL.row(
                                film_film_actor_mainactor.actor().getId().as("id"),
                                select.optional("lastName", film_film_actor_mainactor.actor().LAST_NAME).as("lastName")
                        ).mapping(Functions.nullOnAllNull(Actor::new)).as("mainActors")
                )
                .from(INVENTORY)
                .join(film_film_actor_mainactor)
                .on(no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions.mainActor(INVENTORY.film(), film_film_actor_mainactor))
                .where(INVENTORY.hasIds(inventoryIds))
                .fetchGroups(Record2::value1, Record2::value2);
    }
}