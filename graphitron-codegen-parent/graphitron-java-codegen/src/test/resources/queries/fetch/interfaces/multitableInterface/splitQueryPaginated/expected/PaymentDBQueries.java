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
import no.sikt.graphql.helpers.query.AfterTokenWithTypeName;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.JSONB;
import org.jooq.Row1;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.SelectField;
import org.jooq.SelectJoinStep;
import org.jooq.SelectLimitPercentStep;
import org.jooq.SelectSelectStep;
import org.jooq.impl.DSL;

public class PaymentDBQueries {

    public static Map<Row1<Long>, List<Pair<String, PersonWithEmail>>>  staffAndCustomersForPayment(DSLContext ctx, Set<Row1<Long>> paymentResolverKeys, Integer pageSize, String after, SelectionSet select) {
        var _token = QueryHelper.getOrderByValuesForMultitableInterface(ctx,
                Map.of(
                        "Customer", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray()),
                        "Staff", STAFF.fields(STAFF.getPrimaryKey().getFieldsArray())),
                after);

        var _payment = PAYMENT.as("payment_425747824");

        var unionKeysQuery = staffSortFieldsForStaffAndCustomers(_payment, pageSize, _token).unionAll(customerSortFieldsForStaffAndCustomers(_payment, pageSize, _token));

        var mappedCustomer = customerForStaffAndCustomers();
        var mappedStaff = staffForStaffAndCustomers();

        return ctx.select(
                        DSL.row(_payment.PAYMENT_ID),
                        DSL.multiset(
                                DSL.select(
                                                unionKeysQuery.field("$type"),
                                                mappedCustomer.field(1),
                                                mappedStaff.field(1))
                                        .from(unionKeysQuery)
                                        .leftJoin(mappedCustomer)
                                        .on(unionKeysQuery.field("$pkFields", JSONB.class).eq(mappedCustomer.field("$pkFields", JSONB.class)))
                                        .leftJoin(mappedStaff)
                                        .on(unionKeysQuery.field("$pkFields", JSONB.class).eq(mappedStaff.field("$pkFields", JSONB.class)))
                                        .orderBy(unionKeysQuery.field("$type"), unionKeysQuery.field("$innerRowNum"))
                                        .limit(pageSize + 1)
                        )
                )
                .from(_payment)
                .where(DSL.row(_payment.PAYMENT_ID).in(paymentResolverKeys))
                .fetchMap(
                        r -> r.value1().valuesRow(),
                        r -> r.value2().map(
                                internal_it_ -> {
                                    Record2 _result;
                                    switch (internal_it_.get(0, String.class)) {
                                        case "Customer":
                                            _result = internal_it_.get(1, Record2.class);
                                            break;
                                        case "Staff":
                                            _result = internal_it_.get(2, Record2.class);
                                            break;
                                        default:
                                            throw new RuntimeException(String.format("Querying interface '%s' returned unexpected typeName '%s'", "PersonWithEmail", internal_it_.get(0, String.class)));
                                    }
                                    return Pair.of(_result.get(0, String.class), _result.get(1, PersonWithEmail.class));
                                }
                        )
                );
    }

    private static SelectLimitPercentStep<Record3<String, Integer, JSONB>> customerSortFieldsForStaffAndCustomers(Payment _payment, Integer pageSize, AfterTokenWithTypeName _token) {
        var payment_425747824_customer = _payment.customer().as("customer_1716701867");
        var orderFields = payment_425747824_customer.fields(payment_425747824_customer.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Customer").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Customer"), payment_425747824_customer.CUSTOMER_ID).as("$pkFields"))
                .from(payment_425747824_customer)
                .where(_token == null ? DSL.noCondition() : DSL.inline("Customer").greaterOrEqual(_token.typeName()))
                .and(_token != null && _token.matches("Customer") ? DSL.row(payment_425747824_customer.fields(payment_425747824_customer.getPrimaryKey().getFieldsArray())).gt(DSL.row(_token.fields())) : DSL.noCondition())
                .orderBy(orderFields)
                .limit(pageSize + 1);
    }

    private static SelectJoinStep<Record2<JSONB, Record2<SelectField<String>, SelectSelectStep<Record1<Customer>>>>> customerForStaffAndCustomers() {
        var _customer = CUSTOMER.as("customer_2952383337");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("Customer"), _customer.CUSTOMER_ID).as("$pkFields"),
                        DSL.field(
                                DSL.row(
                                        QueryHelper.getOrderByTokenForMultitableInterface(_customer, _customer.fields(_customer.getPrimaryKey().getFieldsArray()), "Customer"),
                                        DSL.select(DSL.row(_customer.EMAIL).mapping(Functions.nullOnAllNull(Customer::new)))
                                )
                        ).as("$data"))
                .from(_customer);
    }

    private static SelectLimitPercentStep<Record3<String, Integer, JSONB>> staffSortFieldsForStaffAndCustomers(Payment _payment, Integer pageSize, AfterTokenWithTypeName _token) {
        var payment_425747824_staff = _payment.staff().as("staff_3287974561");
        var orderFields = payment_425747824_staff.fields(payment_425747824_staff.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("Staff").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("Staff"), payment_425747824_staff.STAFF_ID).as("$pkFields"))
                .from(payment_425747824_staff)
                .where(_token == null ? DSL.noCondition() : DSL.inline("Staff").greaterOrEqual(_token.typeName()))
                .and(_token != null && _token.matches("Staff") ? DSL.row(payment_425747824_staff.fields(payment_425747824_staff.getPrimaryKey().getFieldsArray())).gt(DSL.row(_token.fields())) : DSL.noCondition())
                .orderBy(orderFields)
                .limit(pageSize + 1);
    }

    private static SelectJoinStep<Record2<JSONB, Record2<SelectField<String>, SelectSelectStep<Record1<Staff>>>>> staffForStaffAndCustomers(
    ) {
        var _staff = STAFF.as("staff_3361087246");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("Staff"), _staff.STAFF_ID).as("$pkFields"),
                        DSL.field(
                                DSL.row(
                                        QueryHelper.getOrderByTokenForMultitableInterface(_staff, _staff.fields(_staff.getPrimaryKey().getFieldsArray()), "Staff"),
                                        DSL.select(DSL.row(_staff.EMAIL).mapping(Functions.nullOnAllNull(Staff::new)))
                                )
                        ).as("$data"))
                .from(_staff);
    }
}
