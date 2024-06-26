package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static List<Address> addressForQuery(DSLContext ctx, String cityID, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                ADDRESS.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Address::new)).as("address")
                )
                .from(ADDRESS)
                .where(ADDRESS.CITY_ID.eq(cityID))
                .orderBy(ADDRESS.getIdFields())
                .fetch(it -> it.into(Address.class));
    }
}
