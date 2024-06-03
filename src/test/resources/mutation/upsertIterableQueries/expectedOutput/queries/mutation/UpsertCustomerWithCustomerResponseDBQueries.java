package fake.code.generated.queries.mutation;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import java.util.Arrays;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
public class UpsertCustomerWithCustomerResponseDBQueries {
    public int upsertCustomerWithCustomerResponse(DSLContext ctx,
            List<CustomerRecord> inputRecordList) {
        return ctx.transactionResult(configuration ->  {
            DSLContext transactionCtx = DSL.using(configuration);
            return Arrays.stream(transactionCtx.batchMerge(inputRecordList).execute()).sum();
        } );
    }
}
