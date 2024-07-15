package fake.code.generated.transform;

import fake.code.generated.mappers.InJavaMapper;
import fake.graphql.example.model.In;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyRecord;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<DummyRecord> inToJavaRecord(List<In> input, String path) {
        return InJavaMapper.toJavaRecord(input, path, this);
    }

    public DummyRecord inToJavaRecord(In input, String path) {
        return inToJavaRecord(List.of(input), path).stream().findFirst().orElse(new DummyRecord());
    }
}
