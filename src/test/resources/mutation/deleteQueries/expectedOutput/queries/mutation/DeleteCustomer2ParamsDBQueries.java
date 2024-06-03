package fake.code.generated.queries.mutation;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import java.lang.String;
import java.util.Arrays;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
public class DeleteCustomer2ParamsDBQueries {
    public int deleteCustomer2Params(DSLContext ctx, CustomerRecord inputRecord, String lastName) {
        return ctx.transactionResult(configuration ->  {
            DSLContext transactionCtx = DSL.using(configuration);
            return Arrays.stream(transactionCtx.batchDelete(inputRecord).execute()).sum();
        } );
    }
}
