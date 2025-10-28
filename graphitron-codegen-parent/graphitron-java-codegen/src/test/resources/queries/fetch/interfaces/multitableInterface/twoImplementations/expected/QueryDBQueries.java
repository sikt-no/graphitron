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
import org.jooq.JSONB;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSeekStepN;
import org.jooq.impl.DSL;

public class QueryDBQueries {

    public static SomeInterface someInterfaceForQuery(DSLContext _iv_ctx, SelectionSet _iv_select) {
        var _iv_unionKeysQuery = customerSortFieldsForSomeInterface().unionAll(addressSortFieldsForSomeInterface());

        var _sjs_address = addressForSomeInterface();
        var _sjs_customer = customerForSomeInterface();

        return _iv_ctx
                .select(
                        DSL.row(
                                _iv_unionKeysQuery.field("$type", String.class),
                                _sjs_address.field("$data"),
                                _sjs_customer.field("$data")
                        ).mapping((_iv_e0, _iv_e1, _iv_e2) -> switch (_iv_e0) {
                                    case "Address" -> (SomeInterface) _iv_e1;
                                    case "Customer" -> (SomeInterface) _iv_e2;
                                    default ->
                                            throw new RuntimeException(String.format("Querying multitable interface/union '%s' returned unexpected typeName '%s'", "SomeInterface", _iv_e0));
                                }
                        ))
                .from(_iv_unionKeysQuery)
                .leftJoin(_sjs_address)
                .on(_iv_unionKeysQuery.field("$pkFields", JSONB.class).eq(_sjs_address.field("$pkFields", JSONB.class)))
                .leftJoin(_sjs_customer)
                .on(_iv_unionKeysQuery.field("$pkFields", JSONB.class).eq(_sjs_customer.field("$pkFields", JSONB.class)))
                .orderBy(_iv_unionKeysQuery.field("$type"), _iv_unionKeysQuery.field("$innerRowNum"))
                .fetchOne(Record1::value1);
    }

    private static SelectSeekStepN<Record3<String, Integer, JSONB>> addressSortFieldsForSomeInterface() {
        var _a_address = ADDRESS.as("address_223244161");
        var _iv_orderFields = _a_address.fields(_a_address.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Address").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(_iv_orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Address"), _a_address.ADDRESS_ID).as("$pkFields"))
                .from(_a_address)
                .orderBy(_iv_orderFields);
    }

    private static SelectJoinStep<Record2<JSONB, Address>> addressForSomeInterface() {
        var _a_address = ADDRESS.as("address_223244161");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("Address"), _a_address.ADDRESS_ID).as("$pkFields"),
                        DSL.field(
                                DSL.select(DSL.row(_a_address.getId()).mapping(Functions.nullOnAllNull(Address::new)))
                        ).as("$data"))
                .from(_a_address);
    }

    private static SelectSeekStepN<Record3<String, Integer, JSONB>> customerSortFieldsForSomeInterface() {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var _iv_orderFields = _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Customer").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(_iv_orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Customer"), _a_customer.CUSTOMER_ID).as("$pkFields"))
                .from(_a_customer)
                .orderBy(_iv_orderFields);
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
                        ).as("$data"))
                .from(_a_customer);
    }
}
