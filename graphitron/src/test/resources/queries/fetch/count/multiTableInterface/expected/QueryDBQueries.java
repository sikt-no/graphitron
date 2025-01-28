package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import java.lang.Integer;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Integer countSomeInterfaceForQuery(DSLContext ctx) {
        var countAddress = DSL.select(DSL.count().as("$count")).from(ADDRESS);
        var countCustomer = DSL.select(DSL.count().as("$count")).from(CUSTOMER);

        var unionCountQuery = countCustomer
                .unionAll(countAddress)
                .asTable();

        return ctx.select(DSL.sum(unionCountQuery.field("$count", Integer.class)))
                .from(unionCountQuery)
                .fetchOne(0, Integer.class);
    }
}