package fake.code.generated.transform;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.CustomerInputTable;
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

    public List<MapperNestedJavaRecord> addressToJavaRecord(List<Address> input, String path) {
        return List.of();
    }

    public List<CustomerRecord> customerInputTableToJOOQRecord(List<CustomerInputTable> input,
                                                               String path) {
        return List.of();
    }

    public MapperNestedJavaRecord addressToJavaRecord(Address input, String path) {
        return new MapperNestedJavaRecord();
    }

    public CustomerRecord customerInputTableToJOOQRecord(CustomerInputTable input, String path) {
        return new CustomerRecord();
    }
}
