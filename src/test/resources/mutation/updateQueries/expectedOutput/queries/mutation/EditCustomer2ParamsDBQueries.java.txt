package fake.code.generated.queries.mutation;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import java.lang.String;
import java.util.Arrays;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomer2ParamsDBQueries {
    public int editCustomer2Params(DSLContext ctx, CustomerRecord inputRecord, String lastName) {
        return Arrays.stream(ctx.batchUpdate(inputRecord).execute()).sum();
    }
}