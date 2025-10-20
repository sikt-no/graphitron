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
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSeekStepN;
import org.jooq.impl.DSL;

public class QueryDBQueries {

    public static List<Payment> paymentsForQuery(DSLContext ctx, String customerId, SelectionSet select) {
        var unionKeysQuery = paymenttypetwoSortFieldsForPayments(customerId).unionAll(paymenttypeoneSortFieldsForPayments(customerId));

        var mappedPaymentTypeOne = paymenttypeoneForPayments();
        var mappedPaymentTypeTwo = paymenttypetwoForPayments();

        return ctx.select(
                        DSL.row(
                                unionKeysQuery.field("$type", String.class),
                                mappedPaymentTypeOne.field("$data"),
                                mappedPaymentTypeTwo.field("$data")
                        ).mapping((a0, a1 , a2) -> switch (a0) {
                                    case "PaymentTypeOne" -> (Payment) a1;
                                    case "PaymentTypeTwo" -> (Payment) a2;
                                    default ->
                                            throw new RuntimeException(String.format("Querying multitable interface/union '%s' returned unexpected typeName '%s'", "Payment", a0));
                                }
                        ))
                .from(unionKeysQuery)
                .leftJoin(mappedPaymentTypeOne)
                .on(unionKeysQuery.field("$pkFields", JSONB.class).eq(mappedPaymentTypeOne.field("$pkFields", JSONB.class)))
                .leftJoin(mappedPaymentTypeTwo)
                .on(unionKeysQuery.field("$pkFields", JSONB.class).eq(mappedPaymentTypeTwo.field("$pkFields", JSONB.class)))
                .orderBy(unionKeysQuery.field("$type"), unionKeysQuery.field("$innerRowNum"))
                .fetch(Record1::value1);
    }

    private static SelectSeekStepN<Record3<String, Integer, JSONB>> paymenttypeoneSortFieldsForPayments(String customerId) {
        var _a_paymentp2007_01 = PAYMENT_P2007_01.as("paymentp200701_3585501569");
        var orderFields = _a_paymentp2007_01.fields(_a_paymentp2007_01.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("PaymentTypeOne").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("PaymentTypeOne"), _a_paymentp2007_01.PAYMENT_ID).as("$pkFields"))
                .from(_a_paymentp2007_01)
                .where(customerId != null ? _a_paymentp2007_01.CUSTOMER_ID.eq(customerId) : DSL.noCondition())
                .orderBy(orderFields);
    }

    private static SelectJoinStep<Record2<JSONB, PaymentTypeOne>> paymenttypeoneForPayments() {
        var _a_paymentp2007_01 = PAYMENT_P2007_01.as("paymentp200701_3585501569");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("PaymentTypeOne"), _a_paymentp2007_01.PAYMENT_ID).as("$pkFields"),
                        DSL.field(
                                DSL.select(DSL.row(_a_paymentp2007_01.AMOUNT).mapping(Functions.nullOnAllNull(PaymentTypeOne::new)))
                        ).as("$data"))
                .from(_a_paymentp2007_01);
    }

    private static SelectSeekStepN<Record3<String, Integer, JSONB>> paymenttypetwoSortFieldsForPayments(String customerId) {
        var _a_paymentp2007_02 = PAYMENT_P2007_02.as("paymentp200702_1287600187");
        var orderFields = _a_paymentp2007_02.fields(_a_paymentp2007_02.getPrimaryKey().getFieldsArray());
        return DSL.select(
                        DSL.inline("PaymentTypeTwo").as("$type"),
                        DSL.rowNumber().over(DSL.orderBy(orderFields)).as("$innerRowNum"),
                        DSL.jsonbArray(DSL.inline("PaymentTypeTwo"), _a_paymentp2007_02.PAYMENT_ID).as("$pkFields"))
                .from(_a_paymentp2007_02)
                .where(customerId != null ? _a_paymentp2007_02.CUSTOMER_ID.eq(customerId) : DSL.noCondition())
                .orderBy(orderFields);
    }

    private static SelectJoinStep<Record2<JSONB, PaymentTypeTwo>> paymenttypetwoForPayments() {
        var _a_paymentp2007_02 = PAYMENT_P2007_02.as("paymentp200702_1287600187");
        return DSL.select(
                        DSL.jsonbArray(DSL.inline("PaymentTypeTwo"), _a_paymentp2007_02.PAYMENT_ID).as("$pkFields"),
                        DSL.field(
                                DSL.select(DSL.row(_a_paymentp2007_02.AMOUNT).mapping(Functions.nullOnAllNull(PaymentTypeTwo::new)))
                        ).as("$data"))
                .from(_a_paymentp2007_02);
    }
}
