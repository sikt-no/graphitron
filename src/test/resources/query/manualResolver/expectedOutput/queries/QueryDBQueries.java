package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Customer;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public List<Customer> customersForQuery(DSLContext ctx, List<String> storeIds,
            SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CUSTOMER.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Customer::new)).as("customers")
                )
                .from(CUSTOMER)
                .where(storeIds.size() > 0 ? CUSTOMER.STORE_ID.in(storeIds) : DSL.noCondition())
                .orderBy(CUSTOMER.getIdFields())
                .fetch(it -> it.into(Customer.class));
    }
}
