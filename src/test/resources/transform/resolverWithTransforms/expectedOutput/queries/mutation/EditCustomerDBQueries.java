package fake.code.generated.queries.mutation;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import java.util.Arrays;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerDBQueries {
    public int editCustomer(DSLContext ctx, CustomerRecord inRecord) {
        return Arrays.stream(ctx.batchUpdate(inRecord).execute()).sum();
    }
}
