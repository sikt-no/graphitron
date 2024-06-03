package fake.code.generated.queries.mutation;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import java.util.Arrays;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
public class EditCustomerWithCustomerResponseDBQueries {
    public int editCustomerWithCustomerResponse(DSLContext ctx, CustomerRecord inputRecord) {
        return ctx.transactionResult(configuration ->  {
            DSLContext transactionCtx = DSL.using(configuration);
            return Arrays.stream(transactionCtx.batchUpdate(inputRecord).execute()).sum();
        } );
    }
}
