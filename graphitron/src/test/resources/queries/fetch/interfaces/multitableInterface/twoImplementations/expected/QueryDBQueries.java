package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.SomeInterface;

import java.lang.Integer;
import java.lang.RuntimeException;
import java.lang.String;

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

    public static SomeInterface someInterfaceForQuery(DSLContext ctx, SelectionSet select) {
        var unionKeysQuery = customerSortFieldsForSomeInterface().unionAll(addressSortFieldsForSomeInterface());

        var mappedAddress = addressForSomeInterface();
        var mappedCustomer = customerForSomeInterface();

        var _result = ctx
                .select(
                        unionKeysQuery.field("$type"),
                        mappedAddress.field("$data").as("$dataForAddress"),
                        mappedCustomer.field("$data").as("$dataForCustomer")
                )
                .from(unionKeysQuery)
                .leftJoin(mappedAddress)
                .on(unionKeysQuery.field("$pkFields", JSON.class).eq(mappedAddress.field("$pkFields", JSON.class)))
                .leftJoin(mappedCustomer)
                .on(unionKeysQuery.field("$pkFields", JSON.class).eq(mappedCustomer.field("$pkFields", JSON.class)))
                .orderBy(unionKeysQuery.field("$type"), unionKeysQuery.field("$innerRowNum"))
                .fetchOne();

        return _result == null ? null : _result.map(
                        internal_it_ -> {
                            switch (internal_it_.get(0, String.class)) {
                                case "Address":
                                    return internal_it_.get("$dataForAddress", SomeInterface.class);
                                case "Customer":
                                    return internal_it_.get("$dataForCustomer", SomeInterface.class);
                                default:
                                    throw new RuntimeException(String.format("Querying interface '%s' returned unexpected typeName '%s'", "SomeInterface", internal_it_.get(0, String.class)));
                            }
                        }

                );
    }

    private static SelectSeekStepN<Record3<String, Integer, JSON>> addressSortFieldsForSomeInterface() {
        var _address = ADDRESS.as("address_2030472956");
        var orderFields = _address.fields(_address.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Address").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonArray(DSL.inline("Address"), _address.ADDRESS_ID).as("$pkFields"))
                .from(_address)
                .orderBy(orderFields);
    }

    private static SelectJoinStep<Record2<JSON, Address>> addressForSomeInterface() {
        var _address = ADDRESS.as("address_2030472956");
        return DSL.select(
                        DSL.jsonArray(DSL.inline("Address"), _address.ADDRESS_ID).as("$pkFields"),
                        DSL.field(
                                DSL.select(DSL.row(_address.getId()).mapping(Functions.nullOnAllNull(Address::new)))
                        ).as("$data"))
                .from(_address);
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
                        ).as("$data"))
                .from(_customer);
    }
}
