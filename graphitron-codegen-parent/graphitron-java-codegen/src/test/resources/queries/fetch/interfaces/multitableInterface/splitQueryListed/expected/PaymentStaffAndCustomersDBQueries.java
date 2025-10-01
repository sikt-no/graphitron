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
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSeekStepN;
import org.jooq.impl.DSL;

public class PaymentStaffAndCustomersDBQueries {

    public static Map<Row1<Long>, List<PersonWithEmail>> staffAndCustomersForPayment(DSLContext ctx, Set<Row1<Long>> paymentResolverKeys, SelectionSet select) {
        var _payment = PAYMENT.as("payment_425747824");
        var unionKeysQuery = staffSortFieldsForStaffAndCustomers(_payment).unionAll(customerSortFieldsForStaffAndCustomers(_payment));

        var mappedCustomer = customerForStaffAndCustomers();
        var mappedStaff = staffForStaffAndCustomers();

        return ctx.select(
                        DSL.row(_payment.PAYMENT_ID),
                        DSL.multiset(
                                DSL.select(
                                                unionKeysQuery.field("$type"),
                                                mappedCustomer.field("$data").as("$dataForCustomer"),
                                                mappedStaff.field("$data").as("$dataForStaff"))
                                        .from(unionKeysQuery)
                                        .leftJoin(mappedCustomer)
                                        .on(unionKeysQuery.field("$pkFields", JSONB.class).eq(mappedCustomer.field("$pkFields", JSONB.class)))
                                        .leftJoin(mappedStaff)
                                        .on(unionKeysQuery.field("$pkFields", JSONB.class).eq(mappedStaff.field("$pkFields", JSONB.class)))
                                        .orderBy(unionKeysQuery.field("$type"), unionKeysQuery.field("$innerRowNum"))
                        )
                )
                .from(_payment)
                .where(DSL.row(_payment.PAYMENT_ID).in(paymentResolverKeys))
                .fetchMap(
                        r -> r.value1().valuesRow(),
                        r -> r.value2().map(
                                internal_it_ -> {
                                    switch (internal_it_.get(0, String.class)) {
                                        case "Customer":
                                            return internal_it_.get("$dataForCustomer", PersonWithEmail.class);
                                        case "Staff":
                                            return internal_it_.get("$dataForStaff", PersonWithEmail.class);
                                        default:
                                            throw new RuntimeException(String.format("Querying interface '%s' returned unexpected typeName '%s'", "PersonWithEmail", internal_it_.get(0, String.class)));
                                    }
                                }
                        )
                );
    }

    private static SelectSeekStepN<Record3<String, Integer, JSONB>> customerSortFieldsForStaffAndCustomers(Payment
                                                                                                                   _payment) {
        var payment_425747824_customer = _payment.customer().as("customer_1716701867");
        var orderFields = payment_425747824_customer.fields(payment_425747824_customer.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Customer").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Customer"), payment_425747824_customer.CUSTOMER_ID).as("$pkFields"))
                .from(payment_425747824_customer)
                .orderBy(orderFields);
    }

    private static SelectJoinStep<Record2<JSONB, Customer>> customerForStaffAndCustomers() {
        var _customer = CUSTOMER.as("customer_2952383337");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("Customer"), _customer.CUSTOMER_ID).as("$pkFields"),
                        DSL.field(
                                DSL.select(DSL.row(_customer.EMAIL).mapping(Functions.nullOnAllNull(Customer::new)))
                        ).as("$data"))
                .from(_customer);
    }

    private static SelectSeekStepN<Record3<String, Integer, JSONB>> staffSortFieldsForStaffAndCustomers(Payment
                                                                                                                _payment) {
        var payment_425747824_staff = _payment.staff().as("staff_3287974561");
        var orderFields = payment_425747824_staff.fields(payment_425747824_staff.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Staff").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Staff"), payment_425747824_staff.STAFF_ID).as("$pkFields"))
                .from(payment_425747824_staff)
                .orderBy(orderFields);
    }

    private static SelectJoinStep<Record2<JSONB, Staff>> staffForStaffAndCustomers
            (
            ) {
        var _staff = STAFF.as("staff_3361087246");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("Staff"), _staff.STAFF_ID).as("$pkFields"),
                        DSL.field(
                                DSL.select(DSL.row(_staff.EMAIL).mapping(Functions.nullOnAllNull(Staff::new)))
                        ).as("$data"))
                .from(_staff);
    }
}
