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

    public static List<Pair<String, SomeInterface>> someInterfaceForQuery(DSLContext _iv_ctx, Integer _iv_pageSize, String _mi_after, SelectionSet _iv_select) {
        var _iv_token = QueryHelper.getOrderByValuesForMultitableInterface(_iv_ctx,
                Map.of("Address", ADDRESS.fields(ADDRESS.getPrimaryKey().getFieldsArray()),
                        "Customer", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())),
                _mi_after);

        var _iv_unionKeysQuery = customerSortFieldsForSomeInterface(_iv_pageSize, _iv_token).unionAll(addressSortFieldsForSomeInterface(_iv_pageSize, _iv_token));

        var _sjs_address = addressForSomeInterface();
        var _sjs_customer = customerForSomeInterface();

        return _iv_ctx.select(
                        DSL.row(
                                _iv_unionKeysQuery.field("$type", String.class),
                                _sjs_address.field("$data"),
                                _sjs_customer.field("$data")
                        ).mapping((_iv_e0, _iv_e1, _iv_e2) -> {
                                    Record2 _iv_result = switch (_iv_e0) {
                                        case "Address" -> (Record2) _iv_e1;
                                        case "Customer" -> (Record2) _iv_e2;
                                        default ->
                                                throw new RuntimeException(String.format("Querying multitable interface/union '%s' returned unexpected typeName '%s'", "SomeInterface", _iv_e0));
                                    };
                                    return Pair.of(_iv_result.get(0, String.class), _iv_result.get(1, SomeInterface.class));
                                }
                        )
                )
                .from(_iv_unionKeysQuery)
                .leftJoin(_sjs_address)
                .on(_iv_unionKeysQuery.field("$pkFields", JSONB.class).eq(_sjs_address.field("$pkFields", JSONB.class)))
                .leftJoin(_sjs_customer)
                .on(_iv_unionKeysQuery.field("$pkFields", JSONB.class).eq(_sjs_customer.field("$pkFields", JSONB.class)))
                .orderBy(_iv_unionKeysQuery.field("$type"), _iv_unionKeysQuery.field("$innerRowNum"))
                .limit(_iv_pageSize + 1)
                .fetch(Record1::value1);
    }

    private static SelectLimitPercentStep<Record3<String, Integer, JSONB>> addressSortFieldsForSomeInterface(Integer _iv_pageSize, AfterTokenWithTypeName _iv_token) {
        var _a_address = ADDRESS.as("address_223244161");
        var _iv_orderFields = _a_address.fields(_a_address.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Address").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(_iv_orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Address"), _a_address.ADDRESS_ID).as("$pkFields"))
                .from(_a_address)
                .where(_iv_token == null ? DSL.noCondition() : DSL.inline("Address").greaterOrEqual(_iv_token.typeName()))
                .and(_iv_token != null && _iv_token.matches("Address") ? DSL.row(_a_address.fields(_a_address.getPrimaryKey().getFieldsArray())).gt(DSL.row(_iv_token.fields())) : DSL.noCondition())
                .orderBy(_iv_orderFields)
                .limit(_iv_pageSize + 1);
    }

    private static SelectJoinStep<Record2<JSONB, Record2<SelectField<String>, SelectSelectStep<Record1<Address>>>>> addressForSomeInterface(
    ) {
        var _a_address = ADDRESS.as("address_223244161");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("Address"), _a_address.ADDRESS_ID).as("$pkFields"),
                        DSL.field(
                                DSL.select(
                                        DSL.row(
                                                QueryHelper.getOrderByTokenForMultitableInterface(_a_address, _a_address.fields(_a_address.getPrimaryKey().getFieldsArray()), "Address"),
                                                DSL.select(DSL.row(_a_address.getId()).mapping(Functions.nullOnAllNull(Address::new)))
                                        )
                                )
                        ).as("$data"))
                .from(_a_address);
    }

    private static SelectLimitPercentStep<Record3<String, Integer, JSONB>> customerSortFieldsForSomeInterface(Integer _iv_pageSize, AfterTokenWithTypeName _iv_token) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var _iv_orderFields = _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Customer").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(_iv_orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Customer"), _a_customer.CUSTOMER_ID).as("$pkFields"))
                .from(_a_customer)
                .where(_iv_token == null ? DSL.noCondition() : DSL.inline("Customer").greaterOrEqual(_iv_token.typeName()))
                .and(_iv_token != null && _iv_token.matches("Customer") ? DSL.row(_a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray())).gt(DSL.row(_iv_token.fields())) : DSL.noCondition())
                .orderBy(_iv_orderFields)
                .limit(_iv_pageSize + 1);
    }

    private static SelectJoinStep<Record2<JSONB, Record2<SelectField<String>, SelectSelectStep<Record1<Customer>>>>> customerForSomeInterface() {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("Customer"), _a_customer.CUSTOMER_ID).as("$pkFields"),
                        DSL.field(
                                DSL.select(
                                        DSL.row(
                                                QueryHelper.getOrderByTokenForMultitableInterface(_a_customer, _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()), "Customer"),
                                                DSL.select(DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(Customer::new)))
                                        )
                                )
                        ).as("$data"))

                .from(_a_customer);
    }
}
