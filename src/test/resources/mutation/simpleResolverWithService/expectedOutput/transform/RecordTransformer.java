package fake.code.generated.transform;

import fake.code.generated.mappers.CustomerTypeMapper;
import fake.code.generated.mappers.EditResponse0TypeMapper;
import fake.code.generated.mappers.EditResponse1TypeMapper;
import fake.code.generated.mappers.EditResponse2TypeMapper;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditResponse0;
import fake.graphql.example.model.EditResponse1;
import fake.graphql.example.model.EditResponse2;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<EditResponse2> editResponse2ToGraphType(List<TestCustomerRecord> input,
                                                        String path) {
        return EditResponse2TypeMapper.toGraphType(input, path, this);
    }

    public List<Customer> customerRecordToGraphType(List<CustomerRecord> input, String path) {
        return CustomerTypeMapper.recordToGraphType(input, path, this);
    }

    public EditResponse2 editResponse2ToGraphType(TestCustomerRecord input, String path) {
        return editResponse2ToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public EditResponse1 editResponse1RecordToGraphType(List<String> input, String path) {
        return EditResponse1TypeMapper.recordToGraphType(input, path, this);
    }

    public EditResponse0 editResponse0RecordToGraphType(String input, String path) {
        return EditResponse0TypeMapper.recordToGraphType(input, path, this);
    }

    public Customer customerRecordToGraphType(CustomerRecord input, String path) {
        return customerRecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }
}
