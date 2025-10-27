package fake.code.generated.transform;

import fake.code.generated.mappers.CustomerTableTypeMapper;
import fake.graphql.example.model.CustomerTable;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.sikt.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment _iv_env) {
        super(_iv_env);
    }

    public List<CustomerTable> customerTableRecordToGraphType(List<CustomerRecord> input, String _iv_path) {
        return CustomerTableTypeMapper.recordToGraphType(input, _iv_path, this);
    }

    public CustomerTable customerTableRecordToGraphType(CustomerRecord input, String _iv_path) {
        return customerTableRecordToGraphType(List.of(input), _iv_path).stream().findFirst().orElse(null);
    }
}
