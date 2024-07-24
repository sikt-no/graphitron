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
        var customer_address_left = CUSTOMER.address().as("address_166982810");
        var address_166982810_customer_left = customer_address_left.customer().as("customer_3093903694");
        var customer_customerstore_store_left = STORE.as("customer_1643242881");
        return ctx
                .select(
                        DSL.row(
                                CUSTOMER.getId(),
                                DSL.row(customer_customerstore_store_left.getId()).mapping(Functions.nullOnAllNull(Store::new))
                        ).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(CUSTOMER)
                .leftJoin(customer_address_left)
                .leftJoin(address_166982810_customer_left)
                .leftJoin(customer_customerstore_store_left)
                .on(no.fellesstudentsystem.graphitron.conditions.StoreTestConditions.customerStore(address_166982810_customer_left, customer_customerstore_store_left))
                .where(CUSTOMER.ID.eq(id))
                .fetchOne(it -> it.into(Customer.class));
    }
}
