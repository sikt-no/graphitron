package fake.code.generated.transform;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.DummyTypeRecord;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.sikt.graphitron.codereferences.records.IDJavaRecord;
import no.sikt.graphitron.codereferences.records.MapperNestedJavaRecord;
import no.sikt.graphql.helpers.transform.AbstractTransformer;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env) {
        super(env);
    }

    public List<DummyTypeRecord> dummyTypeRecordToGraphType(List<IDJavaRecord> input, String path) {
        return List.of();
    }

    public List<Address> addressToGraphType(List<MapperNestedJavaRecord> input, String path) {
        return List.of();
    }

    public DummyTypeRecord dummyTypeRecordToGraphType(IDJavaRecord input, String path) {
        return null;
    }

    public Address addressToGraphType(MapperNestedJavaRecord input, String path) {
        return null;
    }
}
