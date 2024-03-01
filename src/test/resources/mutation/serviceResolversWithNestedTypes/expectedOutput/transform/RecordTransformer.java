package fake.code.generated.transform;

import fake.code.generated.mappers.EditCustomerResponse2TypeMapper;
import fake.code.generated.mappers.EditCustomerResponse3TypeMapper;
import fake.code.generated.mappers.EditCustomerResponse4TypeMapper;
import fake.code.generated.mappers.EditCustomerResponseTypeMapper;
import fake.code.generated.mappers.EditInputLevel1JOOQMapper;
import fake.code.generated.mappers.EditInputLevel2AJOOQMapper;
import fake.code.generated.mappers.EditInputLevel2BJOOQMapper;
import fake.code.generated.mappers.EditInputLevel3JOOQMapper;
import fake.code.generated.mappers.EditInputLevel4JOOQMapper;
import fake.graphql.example.model.EditCustomerResponse;
import fake.graphql.example.model.EditCustomerResponse2;
import fake.graphql.example.model.EditCustomerResponse3;
import fake.graphql.example.model.EditCustomerResponse4;
import fake.graphql.example.model.EditInputLevel1;
import fake.graphql.example.model.EditInputLevel2A;
import fake.graphql.example.model.EditInputLevel2B;
import fake.graphql.example.model.EditInputLevel3;
import fake.graphql.example.model.EditInputLevel4;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse1;
import no.fellesstudentsystem.graphql.exception.ValidationViolationGraphQLException;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class RecordTransformer {
    private final DSLContext ctx;

    private final DataFetchingEnvironment env;

    private final Set<String> arguments;

    private final SelectionSet select;

    private final HashSet<GraphQLError> validationErrors = new HashSet<GraphQLError>();

    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        this.env = env;
        this.ctx = ctx;
        select = new SelectionSet(env.getSelectionSet());
        arguments = Arguments.flattenArgumentKeys(env.getArguments());
    }

    public List<CustomerRecord> editInputLevel1ToJOOQRecord(List<EditInputLevel1> input,
                                                            String path) {
        return EditInputLevel1JOOQMapper.toJOOQRecord(input, path, this);
    }

    public List<CustomerRecord> editInputLevel3ToJOOQRecord(List<EditInputLevel3> input,
                                                            String path) {
        return EditInputLevel3JOOQMapper.toJOOQRecord(input, path, this);
    }

    public List<CustomerRecord> editInputLevel4ToJOOQRecord(List<EditInputLevel4> input,
                                                            String path) {
        return EditInputLevel4JOOQMapper.toJOOQRecord(input, path, this);
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

    public List<EditCustomerResponse4> editCustomerResponse4ToGraphType(
            List<no.fellesstudentsystem.graphitron.records.EditCustomerResponse4> input,
            String path) {
        return EditCustomerResponse4TypeMapper.toGraphType(input, path, this);
    }

    public List<CustomerRecord> editInputLevel2BToJOOQRecord(List<EditInputLevel2B> input,
                                                             String path) {
        return EditInputLevel2BJOOQMapper.toJOOQRecord(input, path, this);
    }

    public List<CustomerRecord> editInputLevel2AToJOOQRecord(List<EditInputLevel2A> input,
                                                             String path) {
        return EditInputLevel2AJOOQMapper.toJOOQRecord(input, path, this);
    }

    public CustomerRecord editInputLevel1ToJOOQRecord(EditInputLevel1 input, String path) {
        return editInputLevel1ToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }

    public CustomerRecord editInputLevel3ToJOOQRecord(EditInputLevel3 input, String path) {
        return editInputLevel3ToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }

    public CustomerRecord editInputLevel4ToJOOQRecord(EditInputLevel4 input, String path) {
        return editInputLevel4ToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
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

    public EditCustomerResponse4 editCustomerResponse4ToGraphType(
            no.fellesstudentsystem.graphitron.records.EditCustomerResponse4 input, String path) {
        return editCustomerResponse4ToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public CustomerRecord editInputLevel2BToJOOQRecord(EditInputLevel2B input, String path) {
        return editInputLevel2BToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }

    public CustomerRecord editInputLevel2AToJOOQRecord(EditInputLevel2A input, String path) {
        return editInputLevel2AToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }

    public void validate() {
        if (!validationErrors.isEmpty()) {
            throw new ValidationViolationGraphQLException(validationErrors);
        }
    }

    public DSLContext getCtx() {
        return ctx;
    }

    public DataFetchingEnvironment getEnv() {
        return env;
    }

    public SelectionSet getSelect() {
        return select;
    }

    public Set<String> getArguments() {
        return arguments;
    }
}
