import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.PaymentDetail;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class CustomerDBQueries {
    private static SelectField<PaymentDetail> paymentDetailsForCustomer_paymentDetail() {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        var _a_customer_2168032777_payment = _a_customer.payment().as("payment_521722061");
        return DSL.row(DSL.row(_a_customer_2168032777_payment.PAYMENT_ID)).mapping(Functions.nullOnAllNull(PaymentDetail::new));
    }
}
