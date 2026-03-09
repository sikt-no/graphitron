package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import java.lang.Integer;
import java.util.List;
import java.util.Map;
import java.util.Set;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.PaymentRecord;
import no.sikt.graphql.helpers.query.QueryHelper;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class PaymentDBQueries {
    public static Map<PaymentRecord, Integer> countStaffAndCustomersForPayment(DSLContext _iv_ctx, Set<PaymentRecord> _rk_payment) {
        var _a_payment = PAYMENT.as("payment_1831371789");
        var _a_payment_1831371789_customer = _a_payment.customer().as("customer_1463568749");
        var _a_payment_1831371789_staff = _a_payment.staff().as("staff_2269035563");

        var countCustomer = DSL.select(DSL.row(_a_payment.PAYMENT_ID).convertFrom(_iv_it -> QueryHelper.intoTableRecord(_iv_it, List.of(_a_payment.PAYMENT_ID))))
                .from(_a_payment)
                .join(_a_payment_1831371789_customer)
                .where(DSL.row(_a_payment.PAYMENT_ID).in(_rk_payment.stream().map(_iv_it -> _iv_it.key().valuesRow()).toList()));

        var countStaff = DSL.select(DSL.row(_a_payment.PAYMENT_ID).convertFrom(_iv_it -> QueryHelper.intoTableRecord(_iv_it, List.of(_a_payment.PAYMENT_ID))))
                .from(_a_payment)
                .join(_a_payment_1831371789_staff)
                .where(DSL.row(_a_payment.PAYMENT_ID).in(_rk_payment.stream().map(_iv_it -> _iv_it.key().valuesRow()).toList()));

        var unionCountQuery = countStaff
                .unionAll(countCustomer)
                .asTable();

        return _iv_ctx.select(unionCountQuery.field(0), DSL.count())
                .from(unionCountQuery)
                .groupBy(unionCountQuery.field(0))
                .fetchMap(_iv_r -> ((PaymentRecord) _iv_r.value1()), Record2::value2);
    }
}
