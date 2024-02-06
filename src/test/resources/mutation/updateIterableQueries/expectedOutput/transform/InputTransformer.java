package fake.code.generated.transform;

import fake.code.generated.mappers.EditInputMapper;
import fake.graphql.example.model.EditInput;
import graphql.schema.DataFetchingEnvironment;
import graphql.GraphQLError;
import java.lang.String;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    public List<CustomerRecord> editInputToJOOQRecord(List<EditInput> input, String path) {
        var records = EditInputMapper.toJOOQRecord(input, path, arguments, ctx);
        return records;
    }

    public CustomerRecord editInputToJOOQRecord(EditInput input, String path) {
        return editInputToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }

    public void validate() {
        if (!validationErrors.isEmpty()) {
            throw new ValidationViolationGraphQLException(validationErrors);
        }
    }
}
