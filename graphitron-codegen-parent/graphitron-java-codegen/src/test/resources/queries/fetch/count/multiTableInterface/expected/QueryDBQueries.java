package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import java.lang.Integer;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Integer countPaymentsForQuery(DSLContext _iv_ctx) {
        var _a_paymentp2007_01 = PAYMENT_P2007_01.as("paymentp200701_3585501569");
        var _a_paymentp2007_02 = PAYMENT_P2007_02.as("paymentp200702_1287600187");
        var countPaymentTypeOne = DSL.select(DSL.count().as("$count")).from(_a_paymentp2007_01);
        var countPaymentTypeTwo = DSL.select(DSL.count().as("$count")).from(_a_paymentp2007_02);
//    public static Integer countPaymentsForQuery(DSLContext ctx) {
//        var _paymentp2007_01 = PAYMENT_P2007_01.as("_01_1056813272");
//        var _paymentp2007_02 = PAYMENT_P2007_02.as("_02_2817843554");
//
//        var countPaymentTypeOne = DSL.select(DSL.count().as("$count")).from(_paymentp2007_01);
//        var countPaymentTypeTwo = DSL.select(DSL.count().as("$count")).from(_paymentp2007_02);

        var unionCountQuery = countPaymentTypeTwo
                .unionAll(countPaymentTypeOne)
                .asTable();

        return _iv_ctx.select(DSL.sum(unionCountQuery.field("$count", Integer.class)))
                .from(unionCountQuery)
                .fetchOne(0, Integer.class);
    }
}
