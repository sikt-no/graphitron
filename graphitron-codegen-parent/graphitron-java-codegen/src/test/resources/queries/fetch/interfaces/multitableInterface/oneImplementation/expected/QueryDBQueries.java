package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.Customer;
import fake.graphql.example.model.SomeInterface;

import java.lang.Integer;
import java.lang.RuntimeException;
import java.lang.String;
import java.util.List;

import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.JSONB;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSeekStepN;
import org.jooq.impl.DSL;

public class QueryDBQueries {

    public static List<SomeInterface> someInterfaceForQuery(DSLContext ctx, SelectionSet select) {
        var unionKeysQuery = customerSortFieldsForSomeInterface();

        var mappedCustomer = customerForSomeInterface();

        return ctx
                .select(
                        DSL.row(

                                unionKeysQuery.field("$type", String.class),
                                mappedCustomer.field("$data")
                        ).mapping((a0, a1) -> switch (a0) {
                                    case "Customer" -> (SomeInterface) a1;
                                    default ->
                                            throw new RuntimeException(String.format("Querying multitable interface/union '%s' returned unexpected typeName '%s'", "SomeInterface", a0));
                                }
                        ))
                .from(unionKeysQuery)
                .leftJoin(mappedCustomer)
                .on(unionKeysQuery.field("$pkFields", JSONB.class).eq(mappedCustomer.field("$pkFields", JSONB.class)))
                .orderBy(unionKeysQuery.field("$type"), unionKeysQuery.field("$innerRowNum"))
                .fetch(Record1::value1);
    }

    private static SelectSeekStepN<Record3<String, Integer, JSONB>> customerSortFieldsForSomeInterface() {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var orderFields = _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Customer").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Customer"), _a_customer.CUSTOMER_ID).as("$pkFields"))
                .from(_a_customer)
                .orderBy(orderFields);
    }

    private static SelectJoinStep<Record2<JSONB, Customer>> customerForSomeInterface() {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.select(
                DSL.jsonbArray(DSL.inline("Customer"), _a_customer.CUSTOMER_ID).as("$pkFields"),
                DSL.field(
                        DSL.select(
                                DSL.row(
                                        _a_customer.getId(),
                                        _a_customer.LAST_NAME
                                ).mapping(Functions.nullOnAllNull(Customer::new))
                        )
                ).as("$data")
        ).from(_a_customer);
    }
}
