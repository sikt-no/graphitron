package fake.code.generated.transform;

import fake.code.generated.mappers.CustomerTypeMapper;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.sikt.graphitron.codereferences.dummyreferences.DummyRecord;
import no.sikt.graphql.helpers.transform.AbstractTransformer;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment _iv_env) {
        super(_iv_env);
    }

    public List<Customer> customerToGraphType(List<DummyRecord> _mi_input, String _iv_path) {
        return CustomerTypeMapper.toGraphType(_mi_input, _iv_path, this);
    }

    public Customer customerToGraphType(DummyRecord _mi_input, String _iv_path) {
        return customerToGraphType(List.of(_mi_input), _iv_path).stream().findFirst().orElse(null);
    }
}
