package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class AddressDBQueries {
    public Map<String, Address> loadAddressByIdsAsNodeRef(DSLContext ctx, Set<String> ids,
            SelectionSet select) {
        return ctx
                .select(
                        ADDRESS.getId(),
                        DSL.row(
                                ADDRESS.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Address::new)).as("id")
                )
                .from(ADDRESS)
                .where(ADDRESS.hasIds(ids))
                .fetchMap(Record2::value1, Record2::value2);
    }
}