package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import java.lang.Integer;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Integer countQueryForQuery(DSLContext ctx) {
        return ctx
                .select(DSL.count())
                .from(CUSTOMER)
                .fetchOne(0, Integer.class);
    }
}
