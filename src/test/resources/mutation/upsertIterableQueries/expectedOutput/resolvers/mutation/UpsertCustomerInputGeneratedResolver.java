package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.UpsertCustomerInputDBQueries;
import fake.graphql.example.package.api.UpsertCustomerInputMutationResolver;
import fake.graphql.example.package.model.UpsertInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class UpsertCustomerInputGeneratedResolver implements UpsertCustomerInputMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private UpsertCustomerInputDBQueries upsertCustomerInputDBQueries;

    @Override
    public CompletableFuture<List<String>> upsertCustomerInput(List<UpsertInput> input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        List<CustomerRecord> inputRecordList = new ArrayList<CustomerRecord>();


        if (input != null) {
            for (int itInputIndex = 0; itInputIndex < input.size(); itInputIndex++) {
                var itInput = input.get(itInputIndex);
                if (itInput == null) continue;
                var inputRecord = new CustomerRecord();
                inputRecord.attach(ctx.configuration());
                if (flatArguments.contains("input/id")) {
                    inputRecord.setId(itInput.getId());
                }
                if (flatArguments.contains("input/customerId")) {
                    inputRecord.setCustomerId(itInput.getCustomerId());
                }
                if (flatArguments.contains("input/firstName")) {
                    inputRecord.setFirstName(itInput.getFirstName());
                }
                if (flatArguments.contains("input/lastName")) {
                    inputRecord.setLastName(itInput.getLastName());
                }
                if (flatArguments.contains("input/storeId")) {
                    inputRecord.setStoreId(itInput.getStoreId());
                }
                if (flatArguments.contains("input/addressId")) {
                    inputRecord.setAddressId(itInput.getAddressId());
                }
                if (flatArguments.contains("input/active")) {
                    inputRecord.setActivebool(itInput.getActive());
                }
                if (flatArguments.contains("input/createdDate")) {
                    inputRecord.setCreateDate(itInput.getCreatedDate());
                }
                inputRecordList.add(inputRecord);
            }
        }

        var rowsUpdated = upsertCustomerInputDBQueries.upsertCustomerInput(ctx, inputRecordList);

        return CompletableFuture.completedFuture(inputRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()));
    }
}