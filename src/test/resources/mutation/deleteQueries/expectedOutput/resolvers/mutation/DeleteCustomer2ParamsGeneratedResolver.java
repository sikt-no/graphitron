package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.DeleteCustomer2ParamsDBQueries;
import fake.graphql.example.package.api.DeleteCustomer2ParamsMutationResolver;
import fake.graphql.example.package.model.DeleteInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class DeleteCustomer2ParamsGeneratedResolver implements DeleteCustomer2ParamsMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private DeleteCustomer2ParamsDBQueries deleteCustomer2ParamsDBQueries;

    @Override
    public CompletableFuture<String> deleteCustomer2Params(DeleteInput input, String lastName,
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

        var rowsUpdated = deleteCustomer2ParamsDBQueries.deleteCustomer2Params(ctx, inputRecord, lastName);

        return CompletableFuture.completedFuture(inputRecord.getId());
    }
}