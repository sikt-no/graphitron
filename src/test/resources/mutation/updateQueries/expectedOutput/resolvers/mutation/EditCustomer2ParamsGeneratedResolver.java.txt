package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomer2ParamsDBQueries;
import fake.graphql.example.package.api.EditCustomer2ParamsMutationResolver;
import fake.graphql.example.package.model.EditInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomer2ParamsGeneratedResolver implements EditCustomer2ParamsMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private EditCustomer2ParamsDBQueries editCustomer2ParamsDBQueries;

    @Override
    public CompletableFuture<String> editCustomer2Params(EditInput input, String lastName,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
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

        var rowsUpdated = editCustomer2ParamsDBQueries.editCustomer2Params(ctx, inputRecord, lastName);

        return CompletableFuture.completedFuture(inputRecord.getId());
    }
}