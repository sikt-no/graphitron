package fake.code.generated.queries.mutation;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import java.util.Arrays;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class MutationDBQueries {
    public static int mutation(DSLContext ctx, CustomerRecord inRecord) {
        return ctx.transactionResult(config -> Arrays.stream(DSL.using(config).batchInsert(inRecord).execute()).sum());
    }
}
