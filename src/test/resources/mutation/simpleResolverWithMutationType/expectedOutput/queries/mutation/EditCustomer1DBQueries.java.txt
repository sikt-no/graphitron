package fake.code.generated.queries.mutation;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import java.lang.String;
import java.util.Arrays;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomer1DBQueries {
    public int editCustomer1(DSLContext ctx, List<String> id, List<CustomerRecord> inRecordList) {
        return Arrays.stream(ctx.batchUpdate(inRecordList).execute()).sum();
    }
}