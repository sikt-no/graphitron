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
    public static Inventory inventoryForQuery(DSLContext ctx, String id, String lastName,
            SelectionSet select) {
        var inventory_film_film_filmactor_film_actor = FILM_ACTOR.as("inventory_2747546302");
        return ctx
                .select(
                        DSL.row(
                                INVENTORY.getId()
                        ).mapping(Functions.nullOnAllNull(Inventory::new))
                )
                .from(INVENTORY)
                .join(inventory_film_film_filmactor_film_actor)
                .on(no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions.film_filmActor(INVENTORY.film(), inventory_film_film_filmactor_film_actor))
                .where(INVENTORY.ID.eq(id))
                .and(inventory_film_film_filmactor_film_actor.actor().LAST_NAME.eq(lastName))
                .fetchOne(it -> it.into(Inventory.class));
    }
}
