package fake.code.generated.queries.mutation;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import java.util.Arrays;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class DeleteCustomerWithCustomerIterableDBQueries {
    public int deleteCustomerWithCustomerIterable(DSLContext ctx,
            List<CustomerRecord> inputRecordList) {
        return Arrays.stream(ctx.batchDelete(inputRecordList).execute()).sum();
    }
}