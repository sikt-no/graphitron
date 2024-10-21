package fake.code.generated.transform;

import fake.code.generated.mappers.CustomerInputTableJOOQMapper;
import fake.graphql.example.model.CustomerInputTable;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env) {
        super(env);
    }

    public List<CustomerRecord> customerInputTableToJOOQRecord(List<CustomerInputTable> input, String path) {
        return CustomerInputTableJOOQMapper.toJOOQRecord(input, path, this);
    }

    public CustomerRecord customerInputTableToJOOQRecord(CustomerInputTable input, String path) {
        return customerInputTableToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }
}
