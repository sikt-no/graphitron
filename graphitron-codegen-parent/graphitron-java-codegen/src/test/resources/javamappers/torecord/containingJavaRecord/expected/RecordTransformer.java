package fake.code.generated.transform;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.DummyInputRecord;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.sikt.graphitron.codereferences.records.IDJavaRecord;
import no.sikt.graphitron.codereferences.records.MapperNestedJavaRecord;
import no.sikt.graphql.helpers.transform.AbstractTransformer;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment _iv_env) {
        super(_iv_env);
    }

    public List<MapperNestedJavaRecord> addressToJavaRecord(List<Address> input, String _iv_path) {
        return List.of();
    }

    public List<IDJavaRecord> dummyInputRecordToJavaRecord(List<DummyInputRecord> input,
                                                           String _iv_path) {
        return List.of();
    }

    public MapperNestedJavaRecord addressToJavaRecord(Address input, String _iv_path) {
        return new MapperNestedJavaRecord();
    }

    public IDJavaRecord dummyInputRecordToJavaRecord(DummyInputRecord input, String _iv_path) {
        return new IDJavaRecord();
    }
}
