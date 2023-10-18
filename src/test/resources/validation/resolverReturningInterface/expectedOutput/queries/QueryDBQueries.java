package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Address;
import fake.graphql.example.package.model.Customer;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public List<Customer> customerForQuery(DSLContext ctx, String id, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CUSTOMER.getId().as("id"),
                                DSL.row(
                                        CUSTOMER.address().getId().as("id")
                                ).mapping(Functions.nullOnAllNull(Address::new)).as("address")
                        ).mapping(Functions.nullOnAllNull(Customer::new)).as("customer")
                )
                .from(CUSTOMER)
                .where(CUSTOMER.ID.eq(id))
                .orderBy(CUSTOMER.getIdFields())
                .fetch(0, Customer.class);
    }
}