package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerUnion;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    private static SelectField<Customer> queryForQuery_customer() {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(DSL.row(_a_customer.EMAIL).mapping(Functions.nullOnAllNull(CustomerUnion::new))).mapping((_iv_e0_0) -> new Customer(_iv_e0_0));
    }
}