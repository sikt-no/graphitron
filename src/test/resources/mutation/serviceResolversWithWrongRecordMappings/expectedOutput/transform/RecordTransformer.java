package fake.code.generated.transform;

import fake.code.generated.mappers.EditInputLevel1JavaMapper;
import fake.code.generated.mappers.EditInputLevel2AJavaMapper;
import fake.code.generated.mappers.EditInputLevel2BJOOQMapper;
import fake.graphql.example.model.EditInputLevel1;
import fake.graphql.example.model.EditInputLevel2A;
import fake.graphql.example.model.EditInputLevel2B;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerInnerRecord;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<TestCustomerRecord> editInputLevel1ToJavaRecord(List<EditInputLevel1> input,
                                                                String path) {
        return EditInputLevel1JavaMapper.toJavaRecord(input, path, this);
    }

    public List<CustomerRecord> editInputLevel2BToJOOQRecord(List<EditInputLevel2B> input,
                                                             String path) {
        return EditInputLevel2BJOOQMapper.toJOOQRecord(input, path, this);
    }

    public List<TestCustomerInnerRecord> editInputLevel2AToJavaRecord(List<EditInputLevel2A> input,
                                                                      String path) {
        return EditInputLevel2AJavaMapper.toJavaRecord(input, path, this);
    }

    public TestCustomerRecord editInputLevel1ToJavaRecord(EditInputLevel1 input, String path) {
        return editInputLevel1ToJavaRecord(List.of(input), path).stream().findFirst().orElse(new TestCustomerRecord());
    }

    public CustomerRecord editInputLevel2BToJOOQRecord(EditInputLevel2B input, String path) {
        return editInputLevel2BToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }

    public TestCustomerInnerRecord editInputLevel2AToJavaRecord(EditInputLevel2A input,
                                                                String path) {
        return editInputLevel2AToJavaRecord(List.of(input), path).stream().findFirst().orElse(new TestCustomerInnerRecord());
    }
}
