package fake.code.generated.transform;

import fake.code.generated.mappers.CustomerTypeMapper;
import fake.code.generated.mappers.EditResponseListTypeMapper;
import fake.code.generated.mappers.EditResponseTypeMapper;
import fake.code.generated.mappers.EditResultTypeMapper;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditResponse;
import fake.graphql.example.model.EditResponseList;
import fake.graphql.example.model.EditResult;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerInnerRecord;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<EditResponse> editResponseToGraphType(List<TestCustomerRecord> input, String path) {
        return EditResponseTypeMapper.toGraphType(input, path, this);
    }

    public List<EditResult> editResultToGraphType(List<TestCustomerInnerRecord> input,
                                                  String path) {
        return EditResultTypeMapper.toGraphType(input, path, this);
    }

    public List<EditResponseList> editResponseListToGraphType(List<TestCustomerRecord> input,
                                                              String path) {
        return EditResponseListTypeMapper.toGraphType(input, path, this);
    }

    public List<Customer> customerRecordToGraphType(List<CustomerRecord> input, String path) {
        return CustomerTypeMapper.recordToGraphType(input, path, this);
    }

    public EditResponse editResponseToGraphType(TestCustomerRecord input, String path) {
        return editResponseToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public EditResult editResultToGraphType(TestCustomerInnerRecord input, String path) {
        return editResultToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public EditResponseList editResponseListToGraphType(TestCustomerRecord input, String path) {
        return editResponseListToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public Customer customerRecordToGraphType(CustomerRecord input, String path) {
        return customerRecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }
}
