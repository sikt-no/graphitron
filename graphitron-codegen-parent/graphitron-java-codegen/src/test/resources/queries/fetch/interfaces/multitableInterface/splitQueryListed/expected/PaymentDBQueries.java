package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Customer;
import fake.graphql.example.model.PersonWithEmail;
import fake.graphql.example.model.Staff;

import java.lang.Integer;
import java.lang.Long;
import java.lang.RuntimeException;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Payment;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.JSONB;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.Row1;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSeekStepN;
import org.jooq.impl.DSL;

public class PaymentDBQueries {
    public static Map<Row1<Long>, List<PersonWithEmail>> staffAndCustomersForPayment(
            DSLContext _iv_ctx, Set<Row1<Long>> _rk_payment, SelectionSet _iv_select) {
        var _a_payment = PAYMENT.as("payment_1831371789");
        var _iv_unionKeysQuery = staffSortFieldsForStaffAndCustomers(_a_payment)
                .unionAll(customerSortFieldsForStaffAndCustomers(_a_payment));

        var _sjs_customer = customerForStaffAndCustomers();
        var _sjs_staff = staffForStaffAndCustomers();

        return _iv_ctx.select(
                        DSL.row(_a_payment.PAYMENT_ID),
                        DSL.multiset(
                                DSL.select(
                                                DSL.row(
                                                        _iv_unionKeysQuery.field("$type", String.class),
                                                        _sjs_customer.field("$data"),
                                                        _sjs_staff.field("$data")
                                                ).mapping((_iv_e0, _iv_e1, _iv_e2) -> switch (_iv_e0) {
                                                            case "Customer" -> (PersonWithEmail) _iv_e1;
                                                            case "Staff" -> (PersonWithEmail) _iv_e2;
                                                            default ->
                                                                    throw new RuntimeException(String.format("Querying multitable interface/union '%s' returned unexpected typeName '%s'", "PersonWithEmail", _iv_e0));
                                                        }
                                                ))
                                        .from(_iv_unionKeysQuery)
                                        .leftJoin(_sjs_customer)
                                        .on(_iv_unionKeysQuery.field("$pkFields", JSONB.class).eq(_sjs_customer.field("$pkFields", JSONB.class)))
                                        .leftJoin(_sjs_staff)
                                        .on(_iv_unionKeysQuery.field("$pkFields", JSONB.class).eq(_sjs_staff.field("$pkFields", JSONB.class)))
                                        .orderBy(_iv_unionKeysQuery.field("$type"), _iv_unionKeysQuery.field("$innerRowNum"))
                        )
                )
                .from(_a_payment)
                .where(DSL.row(_a_payment.PAYMENT_ID).in(_rk_payment))
                .fetchMap(
                        _iv_r -> _iv_r.value1().valuesRow(),
                        _iv_r -> _iv_r.value2().map(Record1::value1)
                );
    }

    private static SelectSeekStepN<Record3<String, Integer, JSONB>> customerSortFieldsForStaffAndCustomers(
            Payment _a_payment) {
        var _a_payment_1831371789_customer = _a_payment.customer().as("customer_1463568749");
        var _iv_orderFields = _a_payment_1831371789_customer.fields(_a_payment_1831371789_customer.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Customer").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(_iv_orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Customer"), _a_payment_1831371789_customer.CUSTOMER_ID).as("$pkFields"))
                .from(_a_payment_1831371789_customer)
                .orderBy(_iv_orderFields);
    }

    private static SelectJoinStep<Record2<JSONB, Customer>> customerForStaffAndCustomers() {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("Customer"), _a_customer.CUSTOMER_ID).as("$pkFields"),
                        DSL.field(
                                DSL.select(DSL.row(_a_customer.EMAIL).mapping(Functions.nullOnAllNull(Customer::new)))
                        ).as("$data"))
                .from(_a_customer);
    }

    private static SelectSeekStepN<Record3<String, Integer, JSONB>> staffSortFieldsForStaffAndCustomers(
            Payment _a_payment) {
        var _a_payment_1831371789_staff = _a_payment.staff().as("staff_2269035563");
        var _iv_orderFields = _a_payment_1831371789_staff.fields(_a_payment_1831371789_staff.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Staff").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(_iv_orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Staff"), _a_payment_1831371789_staff.STAFF_ID).as("$pkFields"))
                .from(_a_payment_1831371789_staff)
                .orderBy(_iv_orderFields);
    }

    private static SelectJoinStep<Record2<JSONB, Staff>> staffForStaffAndCustomers() {
        var _a_staff = STAFF.as("staff_1114567570");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("Staff"), _a_staff.STAFF_ID).as("$pkFields"),
                        DSL.field(
                                DSL.select(DSL.row(_a_staff.EMAIL).mapping(Functions.nullOnAllNull(Staff::new)))
                        ).as("$data"))
                .from(_a_staff);
    }
}
