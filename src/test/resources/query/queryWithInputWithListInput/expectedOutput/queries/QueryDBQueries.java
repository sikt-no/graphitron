package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerFilter;
import java.lang.String;
import java.util.List;
import java.util.stream.Collectors;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static List<Customer> customerForQuery(DSLContext ctx, CustomerFilter filter, String storeId,
            SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CUSTOMER.getId(),
                                select.optional("firstName", CUSTOMER.FIRST_NAME),
                                select.optional("lastName", CUSTOMER.LAST_NAME)
                        ).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(CUSTOMER)
                .where(CUSTOMER.ACTIVEBOOL.eq(filter.getActivebool()))
                .and(storeId != null ? CUSTOMER.STORE_ID.eq(storeId) : DSL.noCondition())
                .and(filter.getName().size() > 0 ?
                        DSL.row(
                                CUSTOMER.FIRST_NAME,
                                CUSTOMER.LAST_NAME
                        ).in(filter.getName().stream().map(input -> DSL.row(
                                input.getFirstName(),
                                input.getLastName())
                        ).collect(Collectors.toList())) :
                        DSL.noCondition())
                .orderBy(CUSTOMER.getIdFields())
                .fetch(it -> it.into(Customer.class));
    }
}
