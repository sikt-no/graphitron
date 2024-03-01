package fake.code.generated.transform;

import fake.code.generated.mappers.CustomerTypeMapper;
import fake.code.generated.mappers.EditInputJavaMapper;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditInput;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;
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

    public List<Customer> customerRecordToGraphType(List<CustomerRecord> input, String path) {
        return CustomerTypeMapper.recordToGraphType(input, path, this);
    }

    public List<TestCustomerRecord> editInputToJavaRecord(List<EditInput> input, String path) {
        return EditInputJavaMapper.toJavaRecord(input, path, this);
    }

    public Customer customerRecordToGraphType(CustomerRecord input, String path) {
        return customerRecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public TestCustomerRecord editInputToJavaRecord(EditInput input, String path) {
        return editInputToJavaRecord(List.of(input), path).stream().findFirst().orElse(new TestCustomerRecord());
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
