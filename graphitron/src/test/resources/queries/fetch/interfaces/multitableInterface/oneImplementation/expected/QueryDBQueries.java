package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Customer;
import fake.graphql.example.model.SomeInterface;

import java.lang.Integer;
import java.lang.RuntimeException;
import java.lang.String;
import java.util.List;

import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.JSON;
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
                        unionKeysQuery.field("$type"),
                        mappedCustomer.field("$data").as("$dataForCustomer")
                )
                .from(unionKeysQuery)
                .leftJoin(mappedCustomer)
                .on(unionKeysQuery.field("$pkFields", JSON.class).eq(mappedCustomer.field("$pkFields", JSON.class)))
                .orderBy(unionKeysQuery.field("$type"), unionKeysQuery.field("$innerRowNum"))
                        .fetch()
                        .map(
                                internal_it_ -> {
                                    switch (internal_it_.get(0, String.class)) {
                                        case "Customer":
                                            return internal_it_.get("$dataForCustomer", SomeInterface.class);
                                        default:
                                            throw new RuntimeException(String.format("Querying interface '%s' returned unexpected typeName '%s'", "SomeInterface", internal_it_.get(0, String.class)));
                                    }
                                }

                        );
    }

    private static SelectSeekStepN<Record3<String, Integer, JSON>> customerSortFieldsForSomeInterface() {
        var _customer = CUSTOMER.as("customer_2952383337");
        var orderFields = _customer.fields(_customer.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Customer").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonArray(DSL.inline("Customer"), _customer.CUSTOMER_ID).as("$pkFields"))
                .from(_customer)
                .orderBy(orderFields);
    }

    private static SelectJoinStep<Record2<JSON, Customer>> customerForSomeInterface() {
        var _customer = CUSTOMER.as("customer_2952383337");
        return DSL.select(
                DSL.jsonArray(DSL.inline("Customer"), _customer.CUSTOMER_ID).as("$pkFields"),
                DSL.field(
                        DSL.select(
                                DSL.row(
                                        _customer.getId(),
                                        _customer.LAST_NAME
                                ).mapping(Functions.nullOnAllNull(Customer::new))
                        )
                ).as("$data")
        ).from(_customer);
    }
}
