package fake.code.generated.transform;

import fake.graphql.example.model.DummyTypeRecord;
import fake.graphql.example.model.Wrapper;
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

    public List<DummyTypeRecord> dummyTypeRecordToGraphType(List<DummyRecord> input, String path) {
        return List.of();
    }

    public Wrapper wrapperRecordToGraphType(DummyRecord input, String path) {
        return null;
    }

    public DummyTypeRecord dummyTypeRecordToGraphType(DummyRecord input, String path) {
        return null;
    }
}
