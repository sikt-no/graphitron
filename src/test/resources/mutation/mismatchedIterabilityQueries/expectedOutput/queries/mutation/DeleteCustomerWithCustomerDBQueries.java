package fake.code.generated.queries.mutation;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import java.util.Arrays;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class DeleteCustomerWithCustomerDBQueries {
    public int deleteCustomerWithCustomer(DSLContext ctx, CustomerRecord inputRecord) {
        return Arrays.stream(ctx.batchDelete(inputRecord).execute()).sum();
    }
}