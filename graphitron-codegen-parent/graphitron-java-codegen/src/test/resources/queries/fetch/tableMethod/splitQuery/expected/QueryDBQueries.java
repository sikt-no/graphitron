import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import fake.graphql.example.model.Customer;
import java.lang.String;
import no.sikt.graphitron.codereferences.services.CustomerTableMethod;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Customer customerForQuery(DSLContext ctx, String first_name,
                                            SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        var customerTableMethod = new CustomerTableMethod();
        _customer = customerTableMethod.customerTable(_customer, first_name);
        return ctx
                .select(
                        DSL.field(
                                DSL.select(DSL.row(_customer.ID).mapping(Functions.nullOnAllNull(Customer::new)))
                                        .from(_customer)
                                        .where(first_name != null ? _customer.FIRST_NAME.eq(first_name) : DSL.noCondition())

                        )
                )
                .from(_customer)
                .fetchOne(it -> it.into(Customer.class));
    }
}