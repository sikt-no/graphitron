package fake.code.generated.transform;

import fake.code.generated.mappers.CustomerTableTypeMapper;
import fake.graphql.example.model.CustomerTable;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<CustomerTable> customerTableRecordToGraphType(List<CustomerRecord> input, String path) {
        return CustomerTableTypeMapper.recordToGraphType(input, path, this);
    }

    public CustomerTable customerTableRecordToGraphType(CustomerRecord input, String path) {
        return customerTableRecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }
}
