package fake.code.generated.transform;

import fake.graphql.example.model.DummyInputRecord;
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

    public List<DummyRecord> dummyInputRecordToJavaRecord(List<DummyInputRecord> input,
                                                          String path) {
        return List.of();
    }

    public DummyRecord dummyInputRecordToJavaRecord(DummyInputRecord input, String path) {
        return new DummyRecord();
    }
}
