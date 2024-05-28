package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Store;
import java.lang.String;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public Store storeForQuery(DSLContext ctx, String id, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                STORE.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Store::new)).as("store")
                )
                .from(STORE)
                .where(STORE.ID.eq(id))
                .fetchOne(0, Store.class);
    }
}