package fake.code.generated.transform;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.CustomerInputTable;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.sikt.graphitron.codereferences.records.MapperNestedJavaRecord;
import no.sikt.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment _iv_env) {
        super(_iv_env);
    }

    public List<MapperNestedJavaRecord> addressToJavaRecord(List<Address> _mi_input, String _iv_path) {
        return List.of();
    }

    public List<CustomerRecord> customerInputTableToJOOQRecord(List<CustomerInputTable> _mi_input,
                                                               String _iv_path) {
        return List.of();
    }

    public MapperNestedJavaRecord addressToJavaRecord(Address _mi_input, String _iv_path) {
        return new MapperNestedJavaRecord();
    }

    public CustomerRecord customerInputTableToJOOQRecord(CustomerInputTable _mi_input, String _iv_path) {
        return new CustomerRecord();
    }
}
