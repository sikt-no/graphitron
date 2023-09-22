package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.InsertCustomersInputAndResponseDBQueries;
import fake.graphql.example.package.api.InsertCustomersInputAndResponseMutationResolver;
import fake.graphql.example.package.model.EditResponse;
import fake.graphql.example.package.model.InsertInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class InsertCustomersInputAndResponseGeneratedResolver implements InsertCustomersInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private InsertCustomersInputAndResponseDBQueries insertCustomersInputAndResponseDBQueries;

    @Override
    public CompletableFuture<List<EditResponse>> insertCustomersInputAndResponse(
            List<InsertInput> input, DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
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

        var rowsUpdated = insertCustomersInputAndResponseDBQueries.insertCustomersInputAndResponse(ctx, inputRecordList);

        var editResponseList = new ArrayList<EditResponse>();
        for (var itInputRecordList : inputRecordList) {
            var editResponse = new EditResponse();
            editResponse.setId(itInputRecordList.getId());
            editResponseList.add(editResponse);
        }

        return CompletableFuture.completedFuture(editResponseList);
    }
}