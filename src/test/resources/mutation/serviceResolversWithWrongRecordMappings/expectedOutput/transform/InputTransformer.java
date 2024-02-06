package fake.code.generated.transform;

import fake.code.generated.mappers.EditInputLevel1JavaMapper;
import fake.code.generated.mappers.EditInputLevel2AJavaMapper;
import fake.code.generated.mappers.EditInputLevel2BMapper;
import fake.graphql.example.model.EditInputLevel1;
import fake.graphql.example.model.EditInputLevel2A;
import fake.graphql.example.model.EditInputLevel2B;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import no.fellesstudentsystem.graphitron.records.TestCustomerInputRecord;
import no.fellesstudentsystem.graphitron.records.TestCustomerInnerInputRecord;
import no.fellesstudentsystem.graphql.exception.ValidationViolationGraphQLException;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class InputTransformer {
    private final DSLContext ctx;

    private final DataFetchingEnvironment env;

    private final Set<String> arguments;

    private final HashSet<GraphQLError> validationErrors = new HashSet<GraphQLError>();

    public InputTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        this.env = env;
        this.ctx = ctx;
        arguments = Arguments.flattenArgumentKeys(env.getArguments());
    }

    public List<TestCustomerInputRecord> editInputLevel1ToJavaRecord(List<EditInputLevel1> input,
                                                                          String path) {
        var records = EditInputLevel1JavaMapper.toJavaRecord(input, path, arguments, ctx, this);
        return records;
    }

    public List<CustomerRecord> editInputLevel2BToJOOQRecord(List<EditInputLevel2B> input,
                                                         String path) {
        var records = EditInputLevel2BMapper.toJOOQRecord(input, path, arguments, ctx);
        return records;
    }

    public List<TestCustomerInnerInputRecord> editInputLevel2AToJavaRecord(List<EditInputLevel2A> input,
                                                             String path) {
        var records = EditInputLevel2AJavaMapper.toJavaRecord(input, path, arguments, ctx, this);
        return records;
    }

    public TestCustomerInputRecord editInputLevel1ToJavaRecord(EditInputLevel1 input, String path) {
        return editInputLevel1ToJavaRecord(List.of(input), path).stream().findFirst().orElse(new TestCustomerInputRecord());
    }

    public CustomerRecord editInputLevel2BToJOOQRecord(EditInputLevel2B input, String path) {
        return editInputLevel2BToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }

    public TestCustomerInnerInputRecord editInputLevel2AToJavaRecord(EditInputLevel2A input, String path) {
        return editInputLevel2AToJavaRecord(List.of(input), path).stream().findFirst().orElse(new TestCustomerInnerInputRecord());
    }

    public void validate() {
        if (!validationErrors.isEmpty()) {
            throw new ValidationViolationGraphQLException(validationErrors);
        }
    }
}
