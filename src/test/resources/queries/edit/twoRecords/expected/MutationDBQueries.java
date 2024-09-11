package fake.code.generated.queries.mutation;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import java.util.ArrayList;
import java.util.Arrays;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class MutationDBQueries {
    public static int mutation(DSLContext ctx, CustomerRecord in1Record, CustomerRecord in2Record) {
        var recordList = new ArrayList();
        recordList.add(in1Record);
        recordList.add(in2Record);
        return ctx.transactionResult(config -> Arrays.stream(DSL.using(config).batchUpdate(recordList).execute()).sum());
    }
}
