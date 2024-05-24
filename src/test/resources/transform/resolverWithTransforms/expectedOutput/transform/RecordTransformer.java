package fake.code.generated.transform;

import fake.code.generated.mappers.EndreInputJOOQMapper;
import fake.graphql.example.model.EndreInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<CustomerRecord> endreInputToJOOQRecord(List<EndreInput> input, String path) {
        return EndreInputJOOQMapper.toJOOQRecord(input, path, this);
    }

    public CustomerRecord endreInputToJOOQRecord(EndreInput input, String path) {
        return endreInputToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }
}
