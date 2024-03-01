package fake.code.generated.transform;

import fake.code.generated.mappers.EditAddressInputJOOQMapper;
import fake.code.generated.mappers.EditAddressResponseTypeMapper;
import fake.graphql.example.model.EditAddressInput;
import fake.graphql.example.model.EditAddressResponse;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphitron.records.EditCustomerAddressResponse;
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

    public List<EditAddressResponse> editAddressResponseToGraphType(
            List<EditCustomerAddressResponse> input, String path) {
        return EditAddressResponseTypeMapper.toGraphType(input, path, this);
    }

    public List<CustomerRecord> editAddressInputToJOOQRecord(List<EditAddressInput> input,
                                                             String path) {
        return EditAddressInputJOOQMapper.toJOOQRecord(input, path, this);
    }

    public EditAddressResponse editAddressResponseToGraphType(EditCustomerAddressResponse input,
                                                              String path) {
        return editAddressResponseToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public CustomerRecord editAddressInputToJOOQRecord(EditAddressInput input, String path) {
        return editAddressInputToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
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
