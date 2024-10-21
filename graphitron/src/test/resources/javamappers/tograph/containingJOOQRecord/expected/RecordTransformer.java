package fake.code.generated.transform;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.CustomerTable;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron.codereferences.records.MapperNestedJavaRecord;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env) {
        super(env);
    }

    public List<Address> addressToGraphType(List<MapperNestedJavaRecord> input, String path) {
        return List.of();
    }

    public List<CustomerTable> customerTableRecordToGraphType(List<CustomerRecord> input,
                                                              String path) {
        return List.of();
    }

    public Address addressToGraphType(MapperNestedJavaRecord input, String path) {
        return null;
    }

    public CustomerTable customerTableRecordToGraphType(CustomerRecord input, String path) {
        return null;
    }
}
