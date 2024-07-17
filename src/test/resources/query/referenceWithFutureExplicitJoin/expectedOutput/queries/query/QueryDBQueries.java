package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.Store;
import java.lang.String;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static Customer customerForQuery(DSLContext ctx, String id, SelectionSet select) {
        var customer_address = ADDRESS.as("customer_785790245");
        var customer_address_customer = CUSTOMER.as("customer_2837757656");
        return ctx
                .select(
                        DSL.row(
                                CUSTOMER.getId(),
                                DSL.row(
                                        customer_address_customer.store().getId()
                                ).mapping(Functions.nullOnAllNull(Store::new))
                        ).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(CUSTOMER)
                .join(customer_address)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .join(customer_address_customer)
                .onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
                .where(CUSTOMER.ID.eq(id))
                .and(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.customerStore(customer_address_customer, customer_address_customer.store()))
                .fetchOne(it -> it.into(Customer.class));
    }
}
