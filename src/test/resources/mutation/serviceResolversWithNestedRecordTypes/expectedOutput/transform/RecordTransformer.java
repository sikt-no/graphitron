package fake.code.generated.transform;

import fake.code.generated.mappers.CustomerTypeMapper;
import fake.code.generated.mappers.EditInputLevel1JavaMapper;
import fake.code.generated.mappers.EditInputLevel2AJavaMapper;
import fake.code.generated.mappers.EditInputLevel2CJavaMapper;
import fake.code.generated.mappers.EditInputLevel3BJOOQMapper;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditInputLevel1;
import fake.graphql.example.model.EditInputLevel2A;
import fake.graphql.example.model.EditInputLevel2C;
import fake.graphql.example.model.EditInputLevel3B;
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

    public List<Customer> customerRecordToGraphType(List<CustomerRecord> input, String path) {
        return CustomerTypeMapper.recordToGraphType(input, path, this);
    }

    public List<CustomerRecord> editInputLevel3BToJOOQRecord(List<EditInputLevel3B> input,
                                                             String path) {
        return EditInputLevel3BJOOQMapper.toJOOQRecord(input, path, this);
    }

    public List<TestCustomerInnerRecord> editInputLevel2AToJavaRecord(List<EditInputLevel2A> input,
                                                                      String path) {
        return EditInputLevel2AJavaMapper.toJavaRecord(input, path, this);
    }

    public List<TestCustomerRecord> editInputLevel2CToJavaRecord(List<EditInputLevel2C> input,
                                                                 String path) {
        return EditInputLevel2CJavaMapper.toJavaRecord(input, path, this);
    }

    public TestCustomerRecord editInputLevel1ToJavaRecord(EditInputLevel1 input, String path) {
        return editInputLevel1ToJavaRecord(List.of(input), path).stream().findFirst().orElse(new TestCustomerRecord());
    }

    public Customer customerRecordToGraphType(CustomerRecord input, String path) {
        return customerRecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public CustomerRecord editInputLevel3BToJOOQRecord(EditInputLevel3B input, String path) {
        return editInputLevel3BToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }

    public TestCustomerInnerRecord editInputLevel2AToJavaRecord(EditInputLevel2A input,
                                                                String path) {
        return editInputLevel2AToJavaRecord(List.of(input), path).stream().findFirst().orElse(new TestCustomerInnerRecord());
    }

    public TestCustomerRecord editInputLevel2CToJavaRecord(EditInputLevel2C input, String path) {
        return editInputLevel2CToJavaRecord(List.of(input), path).stream().findFirst().orElse(new TestCustomerRecord());
    }
}
