package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerDBQueries;
import fake.graphql.example.package.api.EditCustomerMutationResolver;
import fake.graphql.example.package.model.EditInput;
import fake.graphql.example.package.model.EditResponse;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.exception.ValidationViolationGraphQLException;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.fellesstudentsystem.graphql.helpers.validation.RecordValidator;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerGeneratedResolver implements EditCustomerMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private EditCustomerDBQueries editCustomerDBQueries;

    @Override
    public CompletableFuture<EditResponse> editCustomer(String id, EditInput in,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());
        var validationErrors = new HashSet<GraphQLError>();
        var inRecord = new CustomerRecord();
        inRecord.attach(ctx.configuration());

        if (in != null) {
            var pathsForProperties = new HashMap<String, List<String>>();
            var name = in.getName();
            if (name != null) {
                if (flatArguments.contains("in/name/firstName")) {
                    inRecord.setFirstName(name.getFirstName());
                    pathsForProperties.put("firstName", List.of(("in/name/firstName").split("/")));
                }
                if (flatArguments.contains("in/name/surname")) {
                    inRecord.setLastName(name.getSurname());
                    pathsForProperties.put("lastName", List.of(("in/name/surname").split("/")));
                }
            }
            if (flatArguments.contains("in/id")) {
                inRecord.setId(in.getId());
                pathsForProperties.put("id", List.of(("in/id").split("/")));
            }
            validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(inRecord, pathsForProperties, env));
        }

        if (!validationErrors.isEmpty()) {
            throw new ValidationViolationGraphQLException(validationErrors);
        }
        var rowsUpdated = editCustomerDBQueries.editCustomer(ctx, id, inRecord);

        var editResponse = new EditResponse();
        editResponse.setId(id);

        return CompletableFuture.completedFuture(editResponse);
    }
}