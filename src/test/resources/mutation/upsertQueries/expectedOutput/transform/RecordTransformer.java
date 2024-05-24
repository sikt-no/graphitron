package fake.code.generated.transform;

import fake.code.generated.mappers.UpsertInputJOOQMapper;
import fake.graphql.example.model.UpsertInput;
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

    public List<CustomerRecord> upsertInputToJOOQRecord(List<UpsertInput> input, String path) {
        return UpsertInputJOOQMapper.toJOOQRecord(input, path, this);
    }

    public CustomerRecord upsertInputToJOOQRecord(UpsertInput input, String path) {
        return upsertInputToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }
}
