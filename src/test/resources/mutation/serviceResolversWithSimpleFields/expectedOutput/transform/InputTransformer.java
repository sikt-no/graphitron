package fake.code.generated.transform;

import fake.code.generated.mappers.EditInputJavaMapper;
import fake.graphql.example.model.EditInput;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphitron.records.TestCustomerInputRecord;
import no.fellesstudentsystem.graphql.exception.ValidationViolationGraphQLException;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
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

    public List<TestCustomerInputRecord> editInputToJavaRecord(List<EditInput> input, String path) {
        var records = EditInputJavaMapper.toJavaRecord(input, path, arguments, ctx, this);
        return records;
    }

    public TestCustomerInputRecord editInputToJavaRecord(EditInput input, String path) {
        return editInputToJavaRecord(List.of(input), path).stream().findFirst().orElse(new TestCustomerInputRecord());
    }

    public void validate() {
        if (!validationErrors.isEmpty()) {
            throw new ValidationViolationGraphQLException(validationErrors);
        }
    }
}
