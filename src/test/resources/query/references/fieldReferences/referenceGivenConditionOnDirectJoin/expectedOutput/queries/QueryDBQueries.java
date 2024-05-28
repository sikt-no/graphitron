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
    public Customer customerForQuery(DSLContext ctx, String id, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CUSTOMER.getId().as("id"),
                                select.optional("historicalAddressesAddress", CUSTOMER.address().ADDRESS).as("historicalAddressesAddress")
                        ).mapping(Functions.nullOnAllNull(Customer::new)).as("customer")
                )
                .from(CUSTOMER)
                .where(CUSTOMER.ID.eq(id))
                .and(no.fellesstudentsystem.graphitron.conditions.CustomerTestConditions.customerAddress(CUSTOMER, CUSTOMER.address()))
                .fetchOne(0, Customer.class);
    }
}