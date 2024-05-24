package fake.code.generated.transform;

import fake.code.generated.mappers.CustomerTypeMapper;
import fake.code.generated.mappers.EditResponseLevel1TypeMapper;
import fake.code.generated.mappers.EditResponseLevel2ATypeMapper;
import fake.code.generated.mappers.EditResponseLevel2CTypeMapper;
import fake.code.generated.mappers.EditResponseLevel3BTypeMapper;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditResponseLevel1;
import fake.graphql.example.model.EditResponseLevel2A;
import fake.graphql.example.model.EditResponseLevel2C;
import fake.graphql.example.model.EditResponseLevel3B;
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

    public List<EditResponseLevel1> editResponseLevel1ToGraphType(List<TestCustomerRecord> input,
                                                                  String path) {
        return EditResponseLevel1TypeMapper.toGraphType(input, path, this);
    }

    public List<EditResponseLevel2C> editResponseLevel2CToGraphType(List<TestCustomerRecord> input,
                                                                    String path) {
        return EditResponseLevel2CTypeMapper.toGraphType(input, path, this);
    }

    public List<Customer> customerRecordToGraphType(List<CustomerRecord> input, String path) {
        return CustomerTypeMapper.recordToGraphType(input, path, this);
    }

    public List<EditResponseLevel3B> editResponseLevel3BRecordToGraphType(
            List<CustomerRecord> input, String path) {
        return EditResponseLevel3BTypeMapper.recordToGraphType(input, path, this);
    }

    public List<EditResponseLevel2A> editResponseLevel2AToGraphType(
            List<TestCustomerInnerRecord> input, String path) {
        return EditResponseLevel2ATypeMapper.toGraphType(input, path, this);
    }

    public EditResponseLevel1 editResponseLevel1ToGraphType(TestCustomerRecord input, String path) {
        return editResponseLevel1ToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public EditResponseLevel2C editResponseLevel2CToGraphType(TestCustomerRecord input,
                                                              String path) {
        return editResponseLevel2CToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public Customer customerRecordToGraphType(CustomerRecord input, String path) {
        return customerRecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public EditResponseLevel3B editResponseLevel3BRecordToGraphType(CustomerRecord input,
                                                                    String path) {
        return editResponseLevel3BRecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public EditResponseLevel2A editResponseLevel2AToGraphType(TestCustomerInnerRecord input,
                                                              String path) {
        return editResponseLevel2AToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }
}
