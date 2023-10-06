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
            Set<String> inventoryIder, SelectionSet select) {
        var film_mainactors = FILM_ACTOR.as("FILM_4058229257");
        var inventory_film = INVENTORY.film().as("INVENTORY_3033617302");
        var film_mainactors_actor = film_mainactors.actor().as("FILM_ACTOR_3005322313");
        return ctx
                .select(
                        INVENTORY.getId(),
                        DSL.row(
                                film_mainactors_actor.getId().as("id"),
                                select.optional("lastName", film_mainactors_actor.LAST_NAME).as("lastName")
                        ).mapping(Functions.nullOnAllNull(Actor::new)).as("mainActors")
                )
                .from(INVENTORY)
                .join(film_mainactors)
                .on(no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions.mainActor(inventory_film, film_mainactors))
                .where(INVENTORY.hasIds(inventoryIder))
                .fetchGroups(Record2::value1, Record2::value2);
    }
}