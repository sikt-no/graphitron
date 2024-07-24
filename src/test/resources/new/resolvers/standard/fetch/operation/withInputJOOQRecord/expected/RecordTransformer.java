package fake.code.generated.transform;

import fake.graphql.example.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<CustomerRecord> customerToJOOQRecord(List<Customer> input, String path) {
        return List.of();
    }

    public CustomerRecord customerToJOOQRecord(Customer input, String path) {
        return new CustomerRecord();
    }
}
