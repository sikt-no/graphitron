package fake.code.generated.transform;

import fake.code.generated.mappers.DummyInputRecordJavaMapper;
import fake.graphql.example.model.DummyInputRecord;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.sikt.graphitron.codereferences.records.IDJavaRecord;
import no.sikt.graphql.helpers.transform.AbstractTransformer;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env) {
        super(env);
    }

    public List<IDJavaRecord> dummyInputRecordToJavaRecord(List<DummyInputRecord> input, String path) {
        return DummyInputRecordJavaMapper.toJavaRecord(input, path, this);
    }

    public IDJavaRecord dummyInputRecordToJavaRecord(DummyInputRecord input, String path) {
        return dummyInputRecordToJavaRecord(List.of(input), path).stream().findFirst().orElse(new IDJavaRecord());
    }
}
