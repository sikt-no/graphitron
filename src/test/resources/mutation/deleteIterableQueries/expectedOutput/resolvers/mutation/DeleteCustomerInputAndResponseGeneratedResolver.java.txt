package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.DeleteCustomerInputAndResponseDBQueries;
import fake.graphql.example.package.api.DeleteCustomerInputAndResponseMutationResolver;
import fake.graphql.example.package.model.DeleteInput;
import fake.graphql.example.package.model.DeleteResponse;
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

public class DeleteCustomerInputAndResponseGeneratedResolver implements DeleteCustomerInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private DeleteCustomerInputAndResponseDBQueries deleteCustomerInputAndResponseDBQueries;

    @Override
    public CompletableFuture<List<DeleteResponse>> deleteCustomerInputAndResponse(
            List<DeleteInput> input, DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        List<CustomerRecord> inputRecordList = new ArrayList<CustomerRecord>();


        if (input != null) {
            for (int itInputIndex = 0; itInputIndex < input.size(); itInputIndex++) {
                var itInput = input.get(itInputIndex);
                if (itInput == null) continue;
                var inputRecord = new CustomerRecord();
                inputRecord.attach(ctx.configuration());
                if (flatArguments.contains("input/email")) {
                    inputRecord.setEmail(itInput.getEmail());
                }
                if (flatArguments.contains("input/id")) {
                    inputRecord.setId(itInput.getId());
                }
                if (flatArguments.contains("input/firstName")) {
                    inputRecord.setFirstName(itInput.getFirstName());
                }
                inputRecordList.add(inputRecord);
            }
        }

        var rowsUpdated = deleteCustomerInputAndResponseDBQueries.deleteCustomerInputAndResponse(ctx, inputRecordList);

        var deleteResponseList = new ArrayList<DeleteResponse>();
        for (var itInputRecordList : inputRecordList) {
            var deleteResponse = new DeleteResponse();
            deleteResponse.setId(itInputRecordList.getId());
            deleteResponseList.add(deleteResponse);
        }

        return CompletableFuture.completedFuture(deleteResponseList);
    }
}