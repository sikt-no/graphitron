package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.Customer;
import fake.graphql.example.model.SomeInterface;

import java.lang.Integer;
import java.lang.RuntimeException;
import java.lang.String;

import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.JSONB;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSeekStepN;
import org.jooq.impl.DSL;

public class QueryDBQueries {

    public static SomeInterface someInterfaceForQuery(DSLContext ctx, SelectionSet select) {
        var unionKeysQuery = customerSortFieldsForSomeInterface();
        var mappedCustomer = customerForSomeInterface();
        var _result = ctx
                .select(
                        unionKeysQuery.field("$type"),
                        mappedCustomer.field("$data").as("$dataForCustomer"))
                .from(unionKeysQuery)
                .leftJoin(mappedCustomer)
                .on(unionKeysQuery.field("$pkFields", JSONB.class).eq(mappedCustomer.field("$pkFields", JSONB.class)))
                .orderBy(unionKeysQuery.field("$type"), unionKeysQuery.field("$innerRowNum"))
                .fetchOne();

        return _result == null ? null : _result.map(
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

    private static SelectSeekStepN<Record3<String, Integer, JSONB>> customerSortFieldsForSomeInterface() {
        var _customer = CUSTOMER.as("customer_2952383337");
        var orderFields = _customer.fields(_customer.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Customer").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Customer"), _customer.CUSTOMER_ID).as("$pkFields"))
                .from(_customer)
                .orderBy(orderFields);
    }

    private static SelectJoinStep<Record2<JSONB, Customer>> customerForSomeInterface() {
        var _customer = CUSTOMER.as("customer_2952383337");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("Customer"), _customer.CUSTOMER_ID).as("$pkFields"),
                        DSL.field(
                                DSL.select(DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(Customer::new)))
                        ).as("$data"))
                .from(_customer);
    }
}