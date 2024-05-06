package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Payment;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.PaymentRecord;

public class PaymentTypeMapper {
    public static List<Payment> recordToGraphType(List<PaymentRecord> paymentRecord, String path,
                                                  RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var paymentList = new ArrayList<Payment>();

        if (paymentRecord != null) {
            for (var itPaymentRecord : paymentRecord) {
                if (itPaymentRecord == null) continue;
                var payment = new Payment();
                if (select.contains(pathHere + "id")) {
                    payment.setId(itPaymentRecord.getId());
                }

                if (select.contains(pathHere + "amount")) {
                    payment.setAmount(itPaymentRecord.getAmount());
                }

                if (select.contains(pathHere + "date")) {
                    payment.setDate(itPaymentRecord.getPaymentDate());
                }

                if (select.contains(pathHere + "lastUpdate")) {
                    payment.setLastUpdate(itPaymentRecord.getLastUpdate());
                }

                paymentList.add(payment);
            }
        }

        return paymentList;
    }
}
