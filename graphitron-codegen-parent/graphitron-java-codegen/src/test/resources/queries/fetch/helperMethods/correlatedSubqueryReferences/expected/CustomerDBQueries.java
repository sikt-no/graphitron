import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.PaymentDetail;
import java.lang.Long;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record1;
import org.jooq.Row1;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class CustomerDBQueries {
    public static Map<Row1<Long>, List<PaymentDetail>> paymentDetailsForCustomer(DSLContext _iv_ctx,
            Set<Row1<Long>> _rk_customer, SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var _a_customer_2168032777_payment = _a_customer.payment().as("payment_521722061");
        var _iv_orderFields = _a_customer_2168032777_payment.fields(_a_customer_2168032777_payment.getPrimaryKey().getFieldsArray());
        return _iv_ctx
                .select(
                        DSL.row(_a_customer.CUSTOMER_ID),
                        DSL.multiset(
                                DSL.select(paymentDetailsForCustomer_paymentDetail())
                                .from(_a_customer_2168032777_payment)
                                .orderBy(_iv_orderFields)
                        )
                )
                .from(_a_customer)
                .where(DSL.row(_a_customer.CUSTOMER_ID).in(_rk_customer))
                .fetchMap(_iv_r -> _iv_r.value1().valuesRow(), _iv_r -> _iv_r.value2().map(Record1::value1));
    }

    private static SelectField<PaymentDetail> paymentDetailsForCustomer_paymentDetail() {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var _a_customer_2168032777_payment = _a_customer.payment().as("payment_521722061");
        return DSL.row(DSL.row(_a_customer_2168032777_payment.PAYMENT_ID)).mapping(Functions.nullOnAllNull(PaymentDetail::new));
    }
}
