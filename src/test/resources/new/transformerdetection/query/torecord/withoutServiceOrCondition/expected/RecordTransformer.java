package fake.code.generated.transform;

import graphql.schema.DataFetchingEnvironment;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

import java.util.List;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<CustomerRecord> customerInputToJOOQRecord(List<CustomerInput> input, String path) {
        return List.of();
    }

    public CustomerRecord customerInputToJOOQRecord(CustomerInput input, String path) {
        return new CustomerRecord();
    }
}
