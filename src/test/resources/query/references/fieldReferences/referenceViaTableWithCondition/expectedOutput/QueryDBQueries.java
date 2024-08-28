package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Inventory;
import java.lang.String;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static Inventory inventoryForQuery(DSLContext ctx, String id, SelectionSet select) {
        var inventory_film_left = INVENTORY.film().as("film_2557797379");
        var inventory_film_filmactor_film_actor_left = FILM_ACTOR.as("inventory_3785506450");
        var inventory_3785506450_actor_left = inventory_film_filmactor_film_actor_left.actor().as("actor_2652322051");
        return ctx
                .select(
                        DSL.row(
                                INVENTORY.getId(),
                                select.optional("mainActorLastNames", inventory_3785506450_actor_left.LAST_NAME)
                        ).mapping(Functions.nullOnAllNull(Inventory::new))
                )
                .from(INVENTORY)
                .leftJoin(inventory_film_left)
                .leftJoin(inventory_film_filmactor_film_actor_left)
                .on(no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions.film_filmActor(inventory_film_left, inventory_film_filmactor_film_actor_left))
                .leftJoin(inventory_3785506450_actor_left)
                .where(INVENTORY.hasId(id))
                .fetchOne(it -> it.into(Inventory.class));
    }
}
