package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Actor;
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
    public static Map<String, List<Actor>> mainActorsForInventory(DSLContext ctx, Set<String> inventoryIds,
                                                           SelectionSet select) {
        var inventory_film_film_filmactor_film_actor = FILM_ACTOR.as("inventory_2747546302");
        return ctx
                .select(
                        INVENTORY.getId(),
                        DSL.row(
                                inventory_film_film_filmactor_film_actor.actor().getId().as("id"),
                                select.optional("lastName", inventory_film_film_filmactor_film_actor.actor().LAST_NAME).as("lastName")
                        ).mapping(Functions.nullOnAllNull(Actor::new)).as("mainActors")
                )
                .from(INVENTORY)
                .join(inventory_film_film_filmactor_film_actor)
                .on(no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions.film_filmActor(INVENTORY.film(), inventory_film_film_filmactor_film_actor))
                .where(INVENTORY.hasIds(inventoryIds))
                .orderBy(inventory_film_film_filmactor_film_actor.actor().getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
}
