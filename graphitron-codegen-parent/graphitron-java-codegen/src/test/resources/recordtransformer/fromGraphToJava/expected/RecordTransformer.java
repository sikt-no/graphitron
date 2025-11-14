package fake.code.generated.transform;

import fake.code.generated.mappers.DummyInputRecordJavaMapper;
import fake.graphql.example.model.DummyInputRecord;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.sikt.graphitron.codereferences.records.IDJavaRecord;
import no.sikt.graphql.helpers.transform.AbstractTransformer;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment _iv_env) {
        super(_iv_env);
    }

    public List<IDJavaRecord> dummyInputRecordToJavaRecord(List<DummyInputRecord> _mi_input, String _iv_path) {
        return DummyInputRecordJavaMapper.toJavaRecord(_mi_input, _iv_path, this);
    }

    public IDJavaRecord dummyInputRecordToJavaRecord(DummyInputRecord _mi_input, String _iv_path) {
        return dummyInputRecordToJavaRecord(List.of(_mi_input), _iv_path).stream().findFirst().orElse(new IDJavaRecord());
    }
}
