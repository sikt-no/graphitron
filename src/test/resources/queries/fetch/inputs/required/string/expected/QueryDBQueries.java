package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.CustomerTable;
import java.lang.String;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static CustomerTable queryForQuery(DSLContext ctx, String email, SelectionSet select) {
        var _customer = CUSTOMER.as("customer_2952383337");
        return ctx
                .select(DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                .from(_customer)
                .where(_customer.EMAIL.eq(email))
                .fetchOne(it -> it.into(CustomerTable.class));
    }
}
