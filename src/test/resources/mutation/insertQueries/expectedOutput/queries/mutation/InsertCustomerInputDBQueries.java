package fake.code.generated.queries.mutation;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import java.util.Arrays;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class InsertCustomerInputDBQueries {
    public int insertCustomerInput(DSLContext ctx, CustomerRecord inputRecord) {
        return Arrays.stream(ctx.batchInsert(inputRecord).execute()).sum();
    }
}