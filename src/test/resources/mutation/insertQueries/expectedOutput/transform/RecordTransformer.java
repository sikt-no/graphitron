package fake.code.generated.transform;

import fake.code.generated.mappers.InsertInputJOOQMapper;
import fake.graphql.example.model.InsertInput;
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

    public List<CustomerRecord> insertInputToJOOQRecord(List<InsertInput> input, String path) {
        return InsertInputJOOQMapper.toJOOQRecord(input, path, this);
    }

    public CustomerRecord insertInputToJOOQRecord(InsertInput input, String path) {
        return insertInputToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }
}
