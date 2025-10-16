package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import fake.graphql.example.model.CustomerTable;
import java.lang.Long;
import java.util.Map;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.Row1;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class WrapperDBQueries {
    public static Map<Row1<Long>, CustomerTable> queryForWrapper(DSLContext ctx,
                                                                 Set<Row1<Long>> wrapperResolverKeys, SelectionSet select) {
        var _a_address = ADDRESS.as("address_223244161");
        var _a_address_223244161_customer = _a_address.customer().as("customer_1589604633");
        var orderFields = _a_address_223244161_customer.fields(_a_address_223244161_customer.getPrimaryKey().getFieldsArray());
        return ctx
                .select(
                        DSL.row(_a_address.ADDRESS_ID),
                        DSL.field(
                                DSL.select(queryForWrapper_customerTable(_a_address_223244161_customer))
                                        .from(_a_address_223244161_customer)

                        )
                )
                .from(_a_address)
                .where(DSL.row(_a_address.ADDRESS_ID).in(wrapperResolverKeys))
                .fetchMap(r -> r.value1().valuesRow(), Record2::value2);
    }

    private static SelectField<CustomerTable> queryForWrapper_customerTable(
            Customer _a_address_223244161_customer) {
        return DSL.row(_a_address_223244161_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }
}

