package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class MutationDBQueries {
    public static List<CustomerRecord> fetchCustomerRecords(DSLContext _iv_ctx, List<CustomerRecord> _iv_primaryKeys) {
        return _iv_ctx.selectFrom(CUSTOMER)
                .where(DSL.row(CUSTOMER.CUSTOMER_ID).in(_iv_primaryKeys.stream().map(_iv_it -> _iv_it.key().valuesRow()).toList()))
                .fetch();
    }
}
