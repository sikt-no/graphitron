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
import org.jooq.Row1;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSeekStepN;
import org.jooq.impl.DSL;

public class PaymentDBQueries {

    public static Map<Row1<Long>, List<PersonWithEmail>> staffAndCustomersForPayment(DSLContext ctx, Set<Row1<Long>> paymentResolverKeys, SelectionSet select) {
        var _a_payment = PAYMENT.as("payment_1831371789");
        var unionKeysQuery = staffSortFieldsForStaffAndCustomers(_a_payment).unionAll(customerSortFieldsForStaffAndCustomers(_a_payment));

        var mappedCustomer = customerForStaffAndCustomers();
        var mappedStaff = staffForStaffAndCustomers();

        return ctx.select(
                        DSL.row(_a_payment.PAYMENT_ID),
                        DSL.multiset(
                                DSL.select(
                                                DSL.row(
                                                        unionKeysQuery.field("$type", String.class),
                                                        mappedCustomer.field("$data"),
                                                        mappedStaff.field("$data")
                                                ).mapping((a0, a1, a2) -> switch (a0) {
                                                            case "Customer" -> (PersonWithEmail) a1;
                                                            case "Staff" -> (PersonWithEmail) a2;
                                                            default ->
                                                                    throw new RuntimeException(String.format("Querying multitable interface/union '%s' returned unexpected typeName '%s'", "PersonWithEmail", a0));
                                                        }
                                                ))
                                        .from(unionKeysQuery)
                                        .leftJoin(mappedCustomer)
                                        .on(unionKeysQuery.field("$pkFields", JSONB.class).eq(mappedCustomer.field("$pkFields", JSONB.class)))
                                        .leftJoin(mappedStaff)
                                        .on(unionKeysQuery.field("$pkFields", JSONB.class).eq(mappedStaff.field("$pkFields", JSONB.class)))
                                        .orderBy(unionKeysQuery.field("$type"), unionKeysQuery.field("$innerRowNum"))
                        )
                )
                .from(_a_payment)
                .where(DSL.row(_a_payment.PAYMENT_ID).in(paymentResolverKeys))
                .fetchMap(
                        r -> r.value1().valuesRow(),
                        r -> r.value2().map(Record1::value1)
                );
    }

    private static SelectSeekStepN<Record3<String, Integer, JSONB>> customerSortFieldsForStaffAndCustomers(Payment _a_payment) {
        var _a_payment_1831371789_customer = _a_payment.customer().as("customer_1463568749");
        var orderFields = _a_payment_1831371789_customer.fields(_a_payment_1831371789_customer.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Customer").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Customer"), _a_payment_1831371789_customer.CUSTOMER_ID).as("$pkFields"))
                .from(_a_payment_1831371789_customer)
                .orderBy(orderFields);
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

    private static SelectSeekStepN<Record3<String, Integer, JSONB>> staffSortFieldsForStaffAndCustomers(Payment _a_payment) {
        var _a_payment_1831371789_staff = _a_payment.staff().as("staff_2269035563");
        var orderFields = _a_payment_1831371789_staff.fields(_a_payment_1831371789_staff.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Staff").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Staff"), _a_payment_1831371789_staff.STAFF_ID).as("$pkFields"))
                .from(_a_payment_1831371789_staff)
                .orderBy(orderFields);
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
