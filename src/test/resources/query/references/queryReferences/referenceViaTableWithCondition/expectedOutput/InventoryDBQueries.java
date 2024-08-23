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
        var inventory_film = INVENTORY.film().as("film_3370283276");
        var inventory_film_filmactor_film_actor = FILM_ACTOR.as("inventory_572048882");
        var inventory_572048882_actor = inventory_film_filmactor_film_actor.actor().as("actor_2273064954");
        return ctx
                .select(
                        INVENTORY.getId(),
                        DSL.row(
                                inventory_572048882_actor.getId(),
                                select.optional("lastName", inventory_572048882_actor.LAST_NAME)
                        ).mapping(Functions.nullOnAllNull(Actor::new))
                )
                .from(INVENTORY)
                .join(inventory_film)
                .join(inventory_film_filmactor_film_actor)
                .on(no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions.film_filmActor(inventory_film, inventory_film_filmactor_film_actor))
                .join(inventory_572048882_actor)
                .where(INVENTORY.hasIds(inventoryIds))
                .orderBy(inventory_572048882_actor.getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
}
