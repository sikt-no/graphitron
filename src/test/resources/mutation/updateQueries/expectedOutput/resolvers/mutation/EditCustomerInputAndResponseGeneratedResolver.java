package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerInputAndResponseDBQueries;
import fake.graphql.example.package.api.EditCustomerInputAndResponseMutationResolver;
import fake.graphql.example.package.model.EditInput;
import fake.graphql.example.package.model.EditResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerInputAndResponseGeneratedResolver implements EditCustomerInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private EditCustomerInputAndResponseDBQueries editCustomerInputAndResponseDBQueries;

    @Override
    public CompletableFuture<EditResponse> editCustomerInputAndResponse(EditInput input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        var inputRecord = new CustomerRecord();
        inputRecord.attach(ctx.configuration());

        if (input != null) {
            if (flatArguments.contains("input/email")) {
                inputRecord.setEmail(input.getEmail());
            }
            if (flatArguments.contains("input/id")) {
                inputRecord.setId(input.getId());
            }
            if (flatArguments.contains("input/firstName")) {
                inputRecord.setFirstName(input.getFirstName());
            }
        }

        var rowsUpdated = editCustomerInputAndResponseDBQueries.editCustomerInputAndResponse(ctx, inputRecord);

        var editResponse = new EditResponse();
        editResponse.setId(inputRecord.getId());

        return CompletableFuture.completedFuture(editResponse);
    }
}