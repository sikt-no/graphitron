package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.Payment;
import fake.graphql.example.model.PaymentTypeOne;
import fake.graphql.example.model.PaymentTypeTwo;

import java.lang.Integer;
import java.lang.RuntimeException;
import java.lang.String;
import java.util.List;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.JSONB;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSeekStepN;
import org.jooq.impl.DSL;

public class QueryPaymentsDBQueries {

    public static List<Payment> paymentsForQuery(DSLContext ctx, String customerId, SelectionSet select) {
        var unionKeysQuery = paymenttypetwoSortFieldsForPayments(customerId).unionAll(paymenttypeoneSortFieldsForPayments(customerId));

        var mappedPaymentTypeOne = paymenttypeoneForPayments();
        var mappedPaymentTypeTwo = paymenttypetwoForPayments();

        return ctx.select(
                        unionKeysQuery.field("$type"),
                        mappedPaymentTypeOne.field("$data").as("$dataForPaymentTypeOne"),
                        mappedPaymentTypeTwo.field("$data").as("$dataForPaymentTypeTwo"))
                .from(unionKeysQuery)
                .leftJoin(mappedPaymentTypeOne)
                .on(unionKeysQuery.field("$pkFields", JSONB.class).eq(mappedPaymentTypeOne.field("$pkFields", JSONB.class)))
                .leftJoin(mappedPaymentTypeTwo)
                .on(unionKeysQuery.field("$pkFields", JSONB.class).eq(mappedPaymentTypeTwo.field("$pkFields", JSONB.class)))
                .orderBy(unionKeysQuery.field("$type"), unionKeysQuery.field("$innerRowNum"))
                .fetch()
                .map(
                        internal_it_ -> {
                            switch (internal_it_.get(0, String.class)) {
                                case "PaymentTypeOne":
                                    return internal_it_.get("$dataForPaymentTypeOne", Payment.class);
                                case "PaymentTypeTwo":
                                    return internal_it_.get("$dataForPaymentTypeTwo", Payment.class);
                                default:
                                    throw new RuntimeException(String.format("Querying interface '%s' returned unexpected typeName '%s'", "Payment", internal_it_.get(0, String.class)));
                            }
                        }

                );
    }

    private static SelectSeekStepN<Record3<String, Integer, JSONB>> paymenttypeoneSortFieldsForPayments(String customerId) {
        var _paymentp2007_01 = PAYMENT_P2007_01.as("_01_1056813272");
        var orderFields = _paymentp2007_01.fields(_paymentp2007_01.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("PaymentTypeOne").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("PaymentTypeOne"), _paymentp2007_01.PAYMENT_ID).as("$pkFields"))
                .from(_paymentp2007_01)
                .where(customerId != null ? _paymentp2007_01.CUSTOMER_ID.eq(customerId) : DSL.noCondition())
                .orderBy(orderFields);
    }

    private static SelectJoinStep<Record2<JSONB, PaymentTypeOne>> paymenttypeoneForPayments() {
        var _paymentp2007_01 = PAYMENT_P2007_01.as("_01_1056813272");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("PaymentTypeOne"), _paymentp2007_01.PAYMENT_ID).as("$pkFields"),
                        DSL.field(
                                DSL.select(DSL.row(_paymentp2007_01.AMOUNT).mapping(Functions.nullOnAllNull(PaymentTypeOne::new)))
                        ).as("$data"))
                .from(_paymentp2007_01);
    }

    private static SelectSeekStepN<Record3<String, Integer, JSONB>> paymenttypetwoSortFieldsForPayments(String customerId) {
        var _paymentp2007_02 = PAYMENT_P2007_02.as("_02_2817843554");
        var orderFields = _paymentp2007_02.fields(_paymentp2007_02.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("PaymentTypeTwo").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("PaymentTypeTwo"), _paymentp2007_02.PAYMENT_ID).as("$pkFields"))
                .from(_paymentp2007_02)
                .where(customerId != null ? _paymentp2007_02.CUSTOMER_ID.eq(customerId) : DSL.noCondition())
                .orderBy(orderFields);
    }

    private static SelectJoinStep<Record2<JSONB, PaymentTypeTwo>> paymenttypetwoForPayments() {
        var _paymentp2007_02 = PAYMENT_P2007_02.as("_02_2817843554");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("PaymentTypeTwo"), _paymentp2007_02.PAYMENT_ID).as("$pkFields"),
                        DSL.field(
                                DSL.select(DSL.row(_paymentp2007_02.AMOUNT).mapping(Functions.nullOnAllNull(PaymentTypeTwo::new)))
                        ).as("$data"))
                .from(_paymentp2007_02);
    }
}
