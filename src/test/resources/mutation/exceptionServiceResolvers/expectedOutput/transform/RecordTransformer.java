package fake.code.generated.transform;

import fake.code.generated.mappers.CustomerTypeMapper;
import fake.code.generated.mappers.EditCustomerResponse2TypeMapper;
import fake.code.generated.mappers.EditCustomerResponse3TypeMapper;
import fake.code.generated.mappers.EditCustomerResponseTypeMapper;
import fake.code.generated.mappers.EditCustomerResponseUnion1TypeMapper;
import fake.code.generated.mappers.EditCustomerResponseUnion2TypeMapper;
import fake.code.generated.mappers.EditInput2JOOQMapper;
import fake.code.generated.mappers.EditInputJOOQMapper;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditCustomerResponse;
import fake.graphql.example.model.EditCustomerResponse2;
import fake.graphql.example.model.EditCustomerResponse3;
import fake.graphql.example.model.EditCustomerResponseUnion1;
import fake.graphql.example.model.EditCustomerResponseUnion2;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.EditInput2;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse1;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<EditCustomerResponse> editCustomerResponseToGraphType(
            List<EditCustomerResponse1> input, String path) {
        return EditCustomerResponseTypeMapper.toGraphType(input, path, this);
    }

    public List<EditCustomerResponse3> editCustomerResponse3ToGraphType(
            List<no.fellesstudentsystem.graphitron.records.EditCustomerResponse3> input,
            String path) {
        return EditCustomerResponse3TypeMapper.toGraphType(input, path, this);
    }

    public List<EditCustomerResponse2> editCustomerResponse2ToGraphType(
            List<no.fellesstudentsystem.graphitron.records.EditCustomerResponse2> input,
            String path) {
        return EditCustomerResponse2TypeMapper.toGraphType(input, path, this);
    }

    public List<Customer> customerRecordToGraphType(List<CustomerRecord> input, String path) {
        return CustomerTypeMapper.recordToGraphType(input, path, this);
    }

    public List<EditCustomerResponseUnion2> editCustomerResponseUnion2ToGraphType(
            List<EditCustomerResponse1> input, String path) {
        return EditCustomerResponseUnion2TypeMapper.toGraphType(input, path, this);
    }

    public List<CustomerRecord> editInput2ToJOOQRecord(List<EditInput2> input, String path) {
        return EditInput2JOOQMapper.toJOOQRecord(input, path, this);
    }

    public List<EditCustomerResponseUnion1> editCustomerResponseUnion1ToGraphType(
            List<EditCustomerResponse1> input, String path) {
        return EditCustomerResponseUnion1TypeMapper.toGraphType(input, path, this);
    }

    public List<CustomerRecord> editInputToJOOQRecord(List<EditInput> input, String path) {
        return EditInputJOOQMapper.toJOOQRecord(input, path, this);
    }

    public EditCustomerResponse editCustomerResponseToGraphType(EditCustomerResponse1 input,
                                                                String path) {
        return editCustomerResponseToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public EditCustomerResponse3 editCustomerResponse3ToGraphType(
            no.fellesstudentsystem.graphitron.records.EditCustomerResponse3 input, String path) {
        return editCustomerResponse3ToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public EditCustomerResponse2 editCustomerResponse2ToGraphType(
            no.fellesstudentsystem.graphitron.records.EditCustomerResponse2 input, String path) {
        return editCustomerResponse2ToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public Customer customerRecordToGraphType(CustomerRecord input, String path) {
        return customerRecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public EditCustomerResponseUnion2 editCustomerResponseUnion2ToGraphType(
            EditCustomerResponse1 input, String path) {
        return editCustomerResponseUnion2ToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public CustomerRecord editInput2ToJOOQRecord(EditInput2 input, String path) {
        return editInput2ToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }

    public EditCustomerResponseUnion1 editCustomerResponseUnion1ToGraphType(
            EditCustomerResponse1 input, String path) {
        return editCustomerResponseUnion1ToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public CustomerRecord editInputToJOOQRecord(EditInput input, String path) {
        return editInputToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }
}
