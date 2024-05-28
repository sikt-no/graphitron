package fake.code.generated.transform;

import fake.code.generated.mappers.CustomerInputJOOQMapper;
import fake.code.generated.mappers.CustomerJavaTypeMapper;
import fake.code.generated.mappers.CustomerTypeMapper;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerInput;
import fake.graphql.example.model.CustomerJava;
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

    public List<Customer> customerRecordToGraphType(List<CustomerRecord> input, String path) {
        return CustomerTypeMapper.recordToGraphType(input, path, this);
    }

    public List<CustomerRecord> customerInputToJOOQRecord(List<CustomerInput> input, String path) {
        return CustomerInputJOOQMapper.toJOOQRecord(input, path, this);
    }

    public List<CustomerJava> customerJavaToGraphType(List<TestCustomerRecord> input, String path) {
        return CustomerJavaTypeMapper.toGraphType(input, path, this);
    }

    public Customer customerRecordToGraphType(CustomerRecord input, String path) {
        return customerRecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public CustomerRecord customerInputToJOOQRecord(CustomerInput input, String path) {
        return customerInputToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }

    public CustomerJava customerJavaToGraphType(TestCustomerRecord input, String path) {
        return customerJavaToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }
}
