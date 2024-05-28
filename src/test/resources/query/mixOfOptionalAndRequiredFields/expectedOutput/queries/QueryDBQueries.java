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
    public Inventory inventoryForQuery(DSLContext ctx, String id, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                INVENTORY.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Inventory::new)).as("inventory")
                )
                .from(INVENTORY)
                .where(INVENTORY.INVENTORY_ID.eq(id))
                .fetchOne(0, Inventory.class);
    }
}