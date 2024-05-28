package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Address;
import java.lang.String;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public Address addressForQuery(DSLContext ctx, String id, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                ADDRESS.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Address::new)).as("address")
                )
                .from(ADDRESS)
                .where(ADDRESS.ID.eq(id))
                .fetchOne(0, Address.class);
    }
}
