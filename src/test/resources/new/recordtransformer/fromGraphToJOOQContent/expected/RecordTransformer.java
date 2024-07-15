package fake.code.generated.transform;

import fake.code.generated.mappers.InJOOQMapper;
import fake.graphql.example.model.In;
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

    public List<CustomerRecord> inToJOOQRecord(List<In> input, String path) {
        return InJOOQMapper.toJOOQRecord(input, path, this);
    }

    public CustomerRecord inToJOOQRecord(In input, String path) {
        return inToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }
}

