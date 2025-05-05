package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.SomeInterface;

import java.lang.Integer;
import java.lang.RuntimeException;
import java.lang.String;
import java.util.List;
import java.util.Map;

import no.sikt.graphql.helpers.query.AfterTokenWithTypeName;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.JSON;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.SelectField;
import org.jooq.SelectJoinStep;
import org.jooq.SelectLimitPercentStep;
import org.jooq.SelectSelectStep;
import org.jooq.impl.DSL;


public class QueryDBQueries {

    public static List<Pair<String, SomeInterface>> someInterfaceForQuery(DSLContext ctx, Integer pageSize, String after, SelectionSet select) {
        var _token = QueryHelper.getOrderByValuesForMultitableInterface(ctx,
                Map.of("Address", ADDRESS.fields(ADDRESS.getPrimaryKey().getFieldsArray()),
                        "Customer", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())),
                after);

        var unionKeysQuery = customerSortFieldsForSomeInterface(pageSize, _token).unionAll(addressSortFieldsForSomeInterface(pageSize, _token));

        var mappedAddress = addressForSomeInterface();
        var mappedCustomer = customerForSomeInterface();

        return ctx.select(
                        unionKeysQuery.field("$type"),
                        mappedAddress.field(1),
                        mappedCustomer.field(1))
                .from(unionKeysQuery)
                .leftJoin(mappedAddress)
                .on(unionKeysQuery.field("$pkFields", JSON.class).eq(mappedAddress.field("$pkFields", JSON.class)))
                .leftJoin(mappedCustomer)
                .on(unionKeysQuery.field("$pkFields", JSON.class).eq(mappedCustomer.field("$pkFields", JSON.class)))
                .orderBy(unionKeysQuery.field("$type"), unionKeysQuery.field("$innerRowNum"))
                .limit(pageSize + 1)
                .fetch()
                .map(internal_it_ -> {
                            Record2 _result;
                            switch (internal_it_.get(0, String.class)) {
                                case "Address":
                                    _result = internal_it_.get(1, Record2.class);
                                    break;
                                case "Customer":
                                    _result = internal_it_.get(2, Record2.class);
                                    break;
                                default:
                                    throw new RuntimeException(String.format("Querying interface '%s' returned unexpected typeName '%s'", "SomeInterface", internal_it_.get(0, String.class)));
                            }
                            return Pair.of(_result.get(0, String.class), _result.get(1, SomeInterface.class));
                        }
                );
    }

    private static SelectLimitPercentStep<Record3<String, Integer, JSON>> addressSortFieldsForSomeInterface(Integer pageSize, AfterTokenWithTypeName _token) {
        var _address = ADDRESS.as("address_2030472956");
        var orderFields = _address.fields(_address.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Address").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonArray(DSL.inline("Address"), _address.ADDRESS_ID).as("$pkFields"))
                .from(_address)
                .where(_token == null ? DSL.noCondition() : DSL.inline("Address").greaterOrEqual(_token.typeName()))
                .and(_token != null && _token.matches("Address") ? DSL.row(_address.fields(_address.getPrimaryKey().getFieldsArray())).gt(DSL.row(_token.fields())) : DSL.noCondition())
                .orderBy(orderFields)
                .limit(pageSize + 1);
    }

    private static SelectJoinStep<Record2<JSON, Record2<SelectField<String>, SelectSelectStep<Record1<Address>>>>> addressForSomeInterface(
    ) {
        var _address = ADDRESS.as("address_2030472956");
        return DSL.select(
                        DSL.jsonArray(DSL.inline("Address"), _address.ADDRESS_ID).as("$pkFields"),
                        DSL.field(
                                DSL.row(
                                        QueryHelper.getOrderByTokenForMultitableInterface(_address, _address.fields(_address.getPrimaryKey().getFieldsArray()), "Address"),
                                        DSL.select(DSL.row(_address.getId()).mapping(Functions.nullOnAllNull(Address::new)))
                                )
                        ).as("$data"))
                .from(_address);
    }

    private static SelectLimitPercentStep<Record3<String, Integer, JSON>> customerSortFieldsForSomeInterface(Integer pageSize, AfterTokenWithTypeName _token) {
        var _customer = CUSTOMER.as("customer_2952383337");
        var orderFields = _customer.fields(_customer.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Customer").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonArray(DSL.inline("Customer"), _customer.CUSTOMER_ID).as("$pkFields"))
                .from(_customer)
                .where(_token == null ? DSL.noCondition() : DSL.inline("Customer").greaterOrEqual(_token.typeName()))
                .and(_token != null && _token.matches("Customer") ? DSL.row(_customer.fields(_customer.getPrimaryKey().getFieldsArray())).gt(DSL.row(_token.fields())) : DSL.noCondition())
                .orderBy(orderFields)
                .limit(pageSize + 1);
    }

    private static SelectJoinStep<Record2<JSON, Record2<SelectField<String>, SelectSelectStep<Record1<Customer>>>>> customerForSomeInterface() {
        var _customer = CUSTOMER.as("customer_2952383337");
        return DSL.select(
                        DSL.jsonArray(DSL.inline("Customer"), _customer.CUSTOMER_ID).as("$pkFields"),
                        DSL.field(
                                DSL.row(
                                        QueryHelper.getOrderByTokenForMultitableInterface(_customer, _customer.fields(_customer.getPrimaryKey().getFieldsArray()), "Customer"),
                                        DSL.select(DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(Customer::new)))
                                )
                        ).as("$data"))

                .from(_customer);
    }
}
