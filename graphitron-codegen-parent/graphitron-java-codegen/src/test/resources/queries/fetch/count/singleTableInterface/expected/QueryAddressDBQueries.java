package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import java.lang.Integer;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class QueryAddressDBQueries {
    public static Integer countAddressForQuery(DSLContext ctx) {
        var _address = ADDRESS.as("address_2030472956");
        return ctx
                .select(DSL.count())
                .from(_address)
                .where(_address.DISTRICT.in("ONE", "TWO"))
                .fetchOne(0, Integer.class);
    }
}
