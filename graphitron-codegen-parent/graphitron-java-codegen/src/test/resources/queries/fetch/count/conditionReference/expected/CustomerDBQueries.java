import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import java.lang.Integer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import no.sikt.graphql.helpers.query.QueryHelper;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class CustomerDBQueries {
    public static Map<CustomerRecord, Integer> countPaymentsPaginatedForCustomer(DSLContext _iv_ctx,
            Set<CustomerRecord> _rk_customer) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var _a_customer_paymentspaginated = CUSTOMER.as("customer_3807220159");
        var _a_customer_paymentspaginated_payments_payment = PAYMENT.as("payment_4166410193");
        return _iv_ctx
                .select(DSL.row(_a_customer.CUSTOMER_ID).convertFrom(_iv_it -> QueryHelper.intoTableRecord(_iv_it, List.of(_a_customer.CUSTOMER_ID))), DSL.field(
                        DSL.select(DSL.count())
                        .from(_a_customer_paymentspaginated)
                        .join(_a_customer_paymentspaginated_payments_payment)
                        .on(no.sikt.graphitron.codereferences.conditions.ReferenceCustomerCondition.payments(_a_customer_paymentspaginated, _a_customer_paymentspaginated_payments_payment))
                        .where(_a_customer.CUSTOMER_ID.eq(_a_customer_paymentspaginated.CUSTOMER_ID))

                ))
                .from(_a_customer)
                .where(DSL.row(_a_customer.CUSTOMER_ID).in(_rk_customer.stream().map(_iv_it -> _iv_it.key().valuesRow()).toList()))
                .fetchMap(Record2::value1, Record2::value2);
    }
}
