package fake.code.generated.transform;

import fake.code.generated.mappers.EditInput2Mapper;
import fake.code.generated.mappers.EditInputMapper;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.EditInput2;
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

    public List<CustomerRecord> editInput2ToJOOQRecord(List<EditInput2> input, String path) {
        var records = EditInput2Mapper.toJOOQRecord(input, path, arguments, ctx);
        return records;
    }

    public List<CustomerRecord> editInputToJOOQRecord(List<EditInput> input, String path) {
        var records = EditInputMapper.toJOOQRecord(input, path, arguments, ctx);
        return records;
    }

    public CustomerRecord editInput2ToJOOQRecord(EditInput2 input, String path) {
        return editInput2ToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
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
