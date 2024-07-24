package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Customer;
import java.lang.String;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static Customer customerForQuery(DSLContext ctx, String id, SelectionSet select) {
        var customer_customeraddress_address_left = ADDRESS.as("customer_3932835938");
        return ctx
                .select(
                        DSL.row(
                                CUSTOMER.getId(),
                                select.optional("historicalAddressesAddress", customer_customeraddress_address_left.ADDRESS)
                        ).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(CUSTOMER)
                .leftJoin(customer_customeraddress_address_left)
                .on(no.fellesstudentsystem.graphitron.conditions.CustomerTestConditions.customerAddress(CUSTOMER, customer_customeraddress_address_left))
                .where(CUSTOMER.ID.eq(id))
                .fetchOne(it -> it.into(Customer.class));
    }
}
