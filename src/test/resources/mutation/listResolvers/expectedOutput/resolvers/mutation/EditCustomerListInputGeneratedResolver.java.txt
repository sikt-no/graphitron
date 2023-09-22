package fake.code.generated.resolvers.mutation;

import fake.graphql.example.package.api.EditCustomerListInputMutationResolver;
import fake.graphql.example.package.model.EditInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerListInputGeneratedResolver implements EditCustomerListInputMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<String>> editCustomerListInput(List<EditInput> input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var testCustomerService = new TestCustomerService(ctx);
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

        var editCustomerListInputResult = testCustomerService.editCustomerListInput(inputRecordList);

        return CompletableFuture.completedFuture(editCustomerListInputResult);
    }
}