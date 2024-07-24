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
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;
public class InventoryDBQueries {
    public static Map<String, List<Actor>> mainActorsForInventory(DSLContext ctx,
            Set<String> inventoryIds, SelectionSet select) {
        var inventory_film_left = INVENTORY.film().as("film_2557797379");
        var inventory_film_filmactor_film_actor_left = FILM_ACTOR.as("inventory_3785506450");
        var inventory_3785506450_actor_left = inventory_film_filmactor_film_actor_left.actor().as("actor_2652322051");
        return ctx
                .select(
                        INVENTORY.getId(),
                        DSL.row(
                                inventory_3785506450_actor_left.getId(),
                                select.optional("lastName", inventory_3785506450_actor_left.LAST_NAME)
                        ).mapping(Functions.nullOnAllNull(Actor::new))
                )
                .from(INVENTORY)
                .leftJoin(inventory_film_left)
                .leftJoin(inventory_film_filmactor_film_actor_left)
                .on(no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions.film_filmActor(inventory_film_left, inventory_film_filmactor_film_actor_left))
                .leftJoin(inventory_3785506450_actor_left)
                .where(INVENTORY.hasIds(inventoryIds))
                .orderBy(inventory_3785506450_actor_left.getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
}
