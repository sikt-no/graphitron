package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.UpsertCustomerInputDBQueries;
import fake.graphql.example.package.api.UpsertCustomerInputMutationResolver;
import fake.graphql.example.package.model.UpsertInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class UpsertCustomerInputGeneratedResolver implements UpsertCustomerInputMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private UpsertCustomerInputDBQueries upsertCustomerInputDBQueries;

    @Override
    public CompletableFuture<String> upsertCustomerInput(UpsertInput input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        var inputRecord = new CustomerRecord();
        inputRecord.attach(ctx.configuration());

        if (input != null) {
            if (flatArguments.contains("input/id")) {
                inputRecord.setId(input.getId());
            }
            if (flatArguments.contains("input/customerId")) {
                inputRecord.setCustomerId(input.getCustomerId());
            }
            if (flatArguments.contains("input/firstName")) {
                inputRecord.setFirstName(input.getFirstName());
            }
            if (flatArguments.contains("input/lastName")) {
                inputRecord.setLastName(input.getLastName());
            }
            if (flatArguments.contains("input/storeId")) {
                inputRecord.setStoreId(input.getStoreId());
            }
            if (flatArguments.contains("input/addressId")) {
                inputRecord.setAddressId(input.getAddressId());
            }
            if (flatArguments.contains("input/active")) {
                inputRecord.setActivebool(input.getActive());
            }
            if (flatArguments.contains("input/createdDate")) {
                inputRecord.setCreateDate(input.getCreatedDate());
            }
        }

        var rowsUpdated = upsertCustomerInputDBQueries.upsertCustomerInput(ctx, inputRecord);

        return CompletableFuture.completedFuture(inputRecord.getId());
    }
}