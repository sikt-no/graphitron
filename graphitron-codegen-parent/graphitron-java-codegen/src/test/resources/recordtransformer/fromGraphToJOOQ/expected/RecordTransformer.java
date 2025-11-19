package fake.code.generated.transform;

import fake.code.generated.mappers.CustomerInputTableJOOQMapper;
import fake.graphql.example.model.CustomerInputTable;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.sikt.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment _iv_env) {
        super(_iv_env);
    }

    public List<CustomerRecord> customerInputTableToJOOQRecord(List<CustomerInputTable> _mi_input, String _iv_path) {
        return CustomerInputTableJOOQMapper.toJOOQRecord(_mi_input, _iv_path, this);
    }

    public CustomerRecord customerInputTableToJOOQRecord(CustomerInputTable _mi_input, String _iv_path) {
        return customerInputTableToJOOQRecord(List.of(_mi_input), _iv_path).stream().findFirst().orElse(new CustomerRecord());
    }
}
