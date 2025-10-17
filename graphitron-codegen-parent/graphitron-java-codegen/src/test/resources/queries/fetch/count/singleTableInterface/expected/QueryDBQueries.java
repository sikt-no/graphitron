package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import java.lang.Integer;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Integer countAddressForQuery(DSLContext ctx) {
        var _a_address = ADDRESS.as("address_223244161");
        return ctx
                .select(DSL.count())
                .from(_a_address)
                .where(_a_address.DISTRICT.in("ONE", "TWO"))
                .fetchOne(0, Integer.class);
    }
}
