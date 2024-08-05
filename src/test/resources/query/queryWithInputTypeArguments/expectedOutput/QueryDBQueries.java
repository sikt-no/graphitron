package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerEmail;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static List<Customer> customersWithPageForQuery(DSLContext ctx, String active,
                                                           List<Integer> storeIds, Integer pageSize, String after, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CUSTOMER.getId(),
                                select.optional("firstName", CUSTOMER.FIRST_NAME),
                                DSL.row(
                                        select.optional("email/privateEmail", CUSTOMER.EMAIL),
                                        select.optional("email/workEmail", CUSTOMER.EMAIL)
                                ).mapping(Functions.nullOnAllNull(CustomerEmail::new))
                        ).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(CUSTOMER)
                .where(CUSTOMER.ACTIVE.eq(active))
                .and(storeIds != null && storeIds.size() > 0 ? CUSTOMER.STORE_ID.in(storeIds) : DSL.noCondition())
                .orderBy(CUSTOMER.getIdFields())
                .seek(CUSTOMER.getIdValues(after))
                .limit(pageSize + 1)
                .fetch(it -> it.into(Customer.class));
    }

    public static Integer countCustomersWithPageForQuery(DSLContext ctx, String active, List<Integer> storeIds) {
        return ctx
                .select(DSL.count())
                .from(CUSTOMER)
                .where(CUSTOMER.ACTIVE.eq(active))
                .and(storeIds != null && storeIds.size() > 0 ? CUSTOMER.STORE_ID.in(storeIds) : DSL.noCondition())
                .fetchOne(0, Integer.class);
    }
}
