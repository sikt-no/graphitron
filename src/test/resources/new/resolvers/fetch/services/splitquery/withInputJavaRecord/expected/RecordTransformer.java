package fake.code.generated.transform;

import fake.graphql.example.model.Customer;
import fake.graphql.example.model.DummyInputRecord;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyRecord;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
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

    public Customer customerRecordToGraphType(CustomerRecord input, String path) {
        return null;
    }
}
