package fake.code.generated.transform;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.CustomerInputTable;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.MapperNestedJavaRecord;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
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
