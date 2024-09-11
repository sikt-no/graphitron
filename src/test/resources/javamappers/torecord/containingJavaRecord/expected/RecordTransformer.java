package fake.code.generated.transform;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.DummyInputRecord;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron.codereferences.records.IDJavaRecord;
import no.fellesstudentsystem.graphitron.codereferences.records.MapperNestedJavaRecord;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<MapperNestedJavaRecord> addressToJavaRecord(List<Address> input, String path) {
        return List.of();
    }

    public List<IDJavaRecord> dummyInputRecordToJavaRecord(List<DummyInputRecord> input,
                                                           String path) {
        return List.of();
    }

    public MapperNestedJavaRecord addressToJavaRecord(Address input, String path) {
        return new MapperNestedJavaRecord();
    }

    public IDJavaRecord dummyInputRecordToJavaRecord(DummyInputRecord input, String path) {
        return new IDJavaRecord();
    }
}
