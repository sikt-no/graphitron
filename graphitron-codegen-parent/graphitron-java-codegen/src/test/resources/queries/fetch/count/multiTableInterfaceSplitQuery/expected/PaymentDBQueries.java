package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import java.lang.Integer;
import java.lang.Long;
import java.util.Map;
import java.util.Set;

import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.Row1;
import org.jooq.impl.DSL;

public class PaymentDBQueries {
    public static Map<Row1<Long>, Integer> countStaffAndCustomersForPayment(DSLContext ctx, Set<Row1<Long>> paymentResolverKeys) {
        var _payment = PAYMENT.as("payment_425747824");
        var payment_425747824_customer = _payment.customer().as("customer_1716701867");
        var payment_425747824_staff = _payment.staff().as("staff_3287974561");

        var countCustomer = DSL.select(DSL.row(_payment.PAYMENT_ID))
                .from(_payment)
                .join(payment_425747824_customer)
                .where(DSL.row(_payment.PAYMENT_ID).in(paymentResolverKeys));

        var countStaff = DSL.select(DSL.row(_payment.PAYMENT_ID))
                .from(_payment)
                .join(payment_425747824_staff)
                .where(DSL.row(_payment.PAYMENT_ID).in(paymentResolverKeys));

        var unionCountQuery = countStaff
                .unionAll(countCustomer)
                .asTable();

        return ctx.select(unionCountQuery.field(0), DSL.count())
                .from(unionCountQuery)
                .groupBy(unionCountQuery.field(0))
                .fetchMap(r -> ((Record1<Long>) r.value1()).valuesRow(), Record2::value2);
    }
}