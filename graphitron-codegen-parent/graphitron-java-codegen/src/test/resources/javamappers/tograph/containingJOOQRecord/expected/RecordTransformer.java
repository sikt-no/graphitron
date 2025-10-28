package fake.code.generated.transform;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.CustomerTable;
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

    public List<Address> addressToGraphType(List<MapperNestedJavaRecord> _mi_input, String _iv_path) {
        return List.of();
    }

    public List<CustomerTable> customerTableRecordToGraphType(List<CustomerRecord> _mi_input,
                                                              String _iv_path) {
        return List.of();
    }

    public Address addressToGraphType(MapperNestedJavaRecord _mi_input, String _iv_path) {
        return null;
    }

    public CustomerTable customerTableRecordToGraphType(CustomerRecord _mi_input, String _iv_path) {
        return null;
    }
}
