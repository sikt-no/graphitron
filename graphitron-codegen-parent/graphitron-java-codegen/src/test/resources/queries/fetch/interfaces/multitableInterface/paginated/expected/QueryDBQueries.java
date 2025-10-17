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
import org.jooq.JSONB;
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
                .on(unionKeysQuery.field("$pkFields", JSONB.class).eq(mappedAddress.field("$pkFields", JSONB.class)))
                .leftJoin(mappedCustomer)
                .on(unionKeysQuery.field("$pkFields", JSONB.class).eq(mappedCustomer.field("$pkFields", JSONB.class)))
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

    private static SelectLimitPercentStep<Record3<String, Integer, JSONB>> addressSortFieldsForSomeInterface(Integer pageSize, AfterTokenWithTypeName _token) {
        var _a_address = ADDRESS.as("address_223244161");
        var orderFields = _a_address.fields(_a_address.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Address").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Address"), _a_address.ADDRESS_ID).as("$pkFields"))
                .from(_a_address)
                .where(_token == null ? DSL.noCondition() : DSL.inline("Address").greaterOrEqual(_token.typeName()))
                .and(_token != null && _token.matches("Address") ? DSL.row(_a_address.fields(_a_address.getPrimaryKey().getFieldsArray())).gt(DSL.row(_token.fields())) : DSL.noCondition())
                .orderBy(orderFields)
                .limit(pageSize + 1);
    }

    private static SelectJoinStep<Record2<JSONB, Record2<SelectField<String>, SelectSelectStep<Record1<Address>>>>> addressForSomeInterface(
    ) {
        var _a_address = ADDRESS.as("address_223244161");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("Address"), _a_address.ADDRESS_ID).as("$pkFields"),
                        DSL.field(
                                DSL.row(
                                        QueryHelper.getOrderByTokenForMultitableInterface(_a_address, _a_address.fields(_a_address.getPrimaryKey().getFieldsArray()), "Address"),
                                        DSL.select(DSL.row(_a_address.getId()).mapping(Functions.nullOnAllNull(Address::new)))
                                )
                        ).as("$data"))
                .from(_a_address);
    }

    private static SelectLimitPercentStep<Record3<String, Integer, JSONB>> customerSortFieldsForSomeInterface(Integer pageSize, AfterTokenWithTypeName _token) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var orderFields = _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Customer").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Customer"), _a_customer.CUSTOMER_ID).as("$pkFields"))
                .from(_a_customer)
                .where(_token == null ? DSL.noCondition() : DSL.inline("Customer").greaterOrEqual(_token.typeName()))
                .and(_token != null && _token.matches("Customer") ? DSL.row(_a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray())).gt(DSL.row(_token.fields())) : DSL.noCondition())
                .orderBy(orderFields)
                .limit(pageSize + 1);
    }

    private static SelectJoinStep<Record2<JSONB, Record2<SelectField<String>, SelectSelectStep<Record1<Customer>>>>> customerForSomeInterface() {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("Customer"), _a_customer.CUSTOMER_ID).as("$pkFields"),
                        DSL.field(
                                DSL.row(
                                        QueryHelper.getOrderByTokenForMultitableInterface(_a_customer, _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()), "Customer"),
                                        DSL.select(DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(Customer::new)))
                                )
                        ).as("$data"))

                .from(_a_customer);
    }
}
