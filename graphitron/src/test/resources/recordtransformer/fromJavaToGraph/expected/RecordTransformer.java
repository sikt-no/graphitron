package fake.code.generated.transform;

import fake.code.generated.mappers.CustomerTypeMapper;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron.codereferences.dummyreferences.DummyRecord;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env) {
        super(env);
    }

    public List<Customer> customerToGraphType(List<DummyRecord> input, String path) {
        return CustomerTypeMapper.toGraphType(input, path, this);
    }

    public Customer customerToGraphType(DummyRecord input, String path) {
        return customerToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }
}
