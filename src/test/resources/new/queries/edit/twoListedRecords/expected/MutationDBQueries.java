package fake.code.generated.queries.mutation;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class MutationDBQueries {
    public static int mutation(DSLContext ctx, List<CustomerRecord> in1RecordList,
                               List<CustomerRecord> in2RecordList) {
        var recordList = new ArrayList();
        recordList.addAll(in2RecordList);
        recordList.addAll(in1RecordList);
        return ctx.transactionResult(config -> Arrays.stream(DSL.using(config).batchUpdate(recordList).execute()).sum());
    }
}
