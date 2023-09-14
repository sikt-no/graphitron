package fake.code.generated.queries.mutation;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import java.lang.String;
import java.util.Arrays;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class InsertCustomers2ParamsDBQueries {
    public int insertCustomers2Params(DSLContext ctx, List<CustomerRecord> inputRecordList,
            String lastName) {
        return Arrays.stream(ctx.batchInsert(inputRecordList).execute()).sum();
    }
}