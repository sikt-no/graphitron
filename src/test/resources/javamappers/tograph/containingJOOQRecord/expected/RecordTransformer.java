package fake.code.generated.transform;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.CustomerTable;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron.codereferences.records.MapperNestedJavaRecord;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
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
