package fake.code.generated.transform;

import fake.code.generated.mappers.EditInputJOOQMapper;
import fake.code.generated.mappers.EditResponseTypeMapper;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.EditResponse;
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

    public List<EditResponse> editResponseToGraphType(List<EditCustomerResponse1> input,
                                                      String path) {
        return EditResponseTypeMapper.toGraphType(input, path, this);
    }

    public List<CustomerRecord> editInputToJOOQRecord(List<EditInput> input, String path) {
        return EditInputJOOQMapper.toJOOQRecord(input, path, this);
    }

    public EditResponse editResponseToGraphType(EditCustomerResponse1 input, String path) {
        return editResponseToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public CustomerRecord editInputToJOOQRecord(EditInput input, String path) {
        return editInputToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
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
