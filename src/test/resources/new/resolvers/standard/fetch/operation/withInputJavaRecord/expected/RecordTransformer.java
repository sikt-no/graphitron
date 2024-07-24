package fake.code.generated.transform;

import fake.graphql.example.model.Input;
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

    public List<DummyRecord> inputToJavaRecord(List<Input> input, String path) {
        return List.of();
    }

    public DummyRecord inputToJavaRecord(Input input, String path) {
        return new DummyRecord();
    }
}
