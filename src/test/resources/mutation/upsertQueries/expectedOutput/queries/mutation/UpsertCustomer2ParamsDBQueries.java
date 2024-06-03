package fake.code.generated.queries.mutation;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import java.lang.String;
import java.util.Arrays;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
public class UpsertCustomer2ParamsDBQueries {
    public int upsertCustomer2Params(DSLContext ctx, CustomerRecord inputRecord, String lastName) {
        return ctx.transactionResult(configuration ->  {
            DSLContext transactionCtx = DSL.using(configuration);
            return Arrays.stream(transactionCtx.batchMerge(inputRecord).execute()).sum();
        } );
    }
}
